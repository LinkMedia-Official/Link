package io.linkmc.core

import io.linkmc.api.LinkApi.Session
import io.linkmc.core.Database.{lock, maria, sharedLock, sql}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Timestamp}
import java.util.Objects
import scala.annotation.varargs
import scala.util.Using

/** One connection per transaction; connection factory may be backed by a pool. */
final class Database(connections0: Database.Connections) extends AutoCloseable {
  private val connections: Database.Connections = Objects.requireNonNull(connections0)
  private var closeAction: Runnable = () => {}
  @volatile private var nodeOwnerSupplier: java.util.function.Supplier[Session] = null

  /** Backend-only fencing. Startup acquisition uses an unbound connection pool. */
  def nodeOwner(owner: java.util.function.Supplier[Session]): Unit = nodeOwnerSupplier = Objects.requireNonNull(owner)

  private var blobs: WorldBlobs = new WorldBlobs(null)
  def worldBlobs(): WorldBlobs = blobs
  def worldStorage(store: WorldBlobs.Store): Unit = blobs = new WorldBlobs(store)

  override def close(): Unit = closeAction.run()

  @throws[Exception]
  def transaction[T](work: Database.Work[T]): T = runTransaction(work, false)

  @throws[Exception]
  private[core] def exclusiveNodeTransaction[T](work: Database.Work[T]): T = runTransaction(work, true)

  // ── Server-thread SQL watchdog ─────────────────────────────────────────────
  // SQL on the game thread stalls every player. The backend registers a probe; any transaction it flags that takes
  // longer than the threshold is logged with its call site (rate limited) so remaining blocking paths are visible.
  @throws[Exception]
  private def runTransaction[T](work: Database.Work[T], exclusiveNode: Boolean): T = {
    val watched: Boolean =
      try Database.gameThread.getAsBoolean()
      catch { case probeFailure: RuntimeException => false }
    if (!watched) transactionNow(work, exclusiveNode)
    else {
      val started = System.nanoTime()
      try transactionNow(work, exclusiveNode)
      finally {
        val elapsed = System.nanoTime() - started
        val previous = Database.lastSlowReport.get()
        if (elapsed > Database.SLOW_GAME_THREAD_NANOS && (previous == Long.MinValue || started - previous > java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) && Database.lastSlowReport.compareAndSet(previous, started))
          Database.LOG.log(System.Logger.Level.WARNING, "Link SQL blocked the server thread for " + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms", new Throwable("call site"))
      }
    }
  }

  @throws[Exception]
  private def transactionNow[T](work: Database.Work[T], exclusiveNode: Boolean): T =
    Using.resource(connections.open()) { c =>
      c.setAutoCommit(false)
      c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
      try {
        Using.resource(c.createStatement()) { s =>
          if (maria(c)) {
            s.execute("SET SESSION innodb_lock_wait_timeout=5")
            s.execute("SET SESSION max_statement_time=10")
            s.execute("SET time_zone='+00:00'")
          } else {
            s.execute("SET LOCAL statement_timeout = '10s'")
            s.execute("SET LOCAL lock_timeout = '5s'")
            s.execute("SET LOCAL synchronous_commit = on")
          }
        }
        val owner = nodeOwnerSupplier
        if (owner != null) {
          val session = Objects.requireNonNull(owner.get(), "Missing backend owner")
          if (exclusiveNode) lock(c, "node-startup:" + session.node()) else sharedLock(c, "node-startup:" + session.node())
          Using.resource(sql(c, "SELECT 1 FROM link_sessions WHERE player=? AND node=? AND epoch=? AND state='ACTIVE' AND expires_at>clock_timestamp()", session.player(), session.node(), java.lang.Long.valueOf(session.epoch()))) { s =>
            Using.resource(s.executeQuery()) { r =>
              if (!r.next()) throw ConflictException.ownershipLost("Backend instance was replaced or its lease expired")
            }
          }
        }
        val result = work.run(c)
        c.commit()
        result
      } catch {
        case e: Exception =>
          try c.rollback()
          catch { case rollback: SQLException => e.addSuppressed(rollback) }
          throw e
      }
    }

  @throws[Exception]
  def migrate(): Unit = {
    val migratedMaria = Using.resource(connections.open()) { c =>
      if (maria(c)) { migrateMaria(c); true } else false
    }
    if (migratedMaria) return
    val stream = classOf[Database].getResourceAsStream("/link/schema.sql")
    if (stream == null) throw new IOException("Missing Link schema")
    Using.resource(stream) { input =>
      val sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
      transaction[AnyRef] { c =>
        lock(c, "schema")
        Using.resource(c.createStatement()) { s => s.execute(sql) }
        Using.resource(c.createStatement()) { s =>
          Using.resource(s.executeQuery("SELECT max(version) FROM link_schema")) { r =>
            r.next()
            if (r.getInt(1) != 1) throw new SQLException("Unsupported Link schema")
          }
        }
        null
      }
    }
  }

  @throws[Exception]
  private def migrateMaria(c: Connection): Unit = {
    Using.resource(c.createStatement()) { s =>
      Using.resource(s.executeQuery("SELECT @@innodb_flush_log_at_trx_commit,@@log_bin,@@sync_binlog")) { r =>
        if (!r.next() || r.getInt(1) != 1 || (r.getBoolean(2) && r.getInt(3) != 1)) throw new SQLException("MariaDB requires innodb_flush_log_at_trx_commit=1 and sync_binlog=1 when binlog is enabled")
      }
    }
    // MariaDB DDL implicitly commits: use a connection lock, not a transaction lock.
    Using.resource(c.createStatement()) { s =>
      Using.resource(s.executeQuery("SELECT GET_LOCK('link-schema-v1',10)")) { r =>
        if (!r.next() || r.getInt(1) != 1) throw new SQLException("Schema lock timeout")
      }
    }
    try {
      val stream = classOf[Database].getResourceAsStream("/link/schema-mariadb.sql")
      if (stream == null) throw new IOException("Missing MariaDB schema")
      Using.resource(stream) { input =>
        val statements = new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")
        var i = 0
        while (i < statements.length) {
          val statement = statements(i)
          if (!statement.isBlank()) Using.resource(c.createStatement()) { s => s.execute(statement) }
          i += 1
        }
      }
      Using.resource(c.createStatement()) { s =>
        Using.resource(s.executeQuery("SELECT max(version) FROM link_schema")) { r =>
          if (!r.next() || r.getInt(1) != 1) throw new SQLException("Unsupported Link schema")
        }
      }
    } finally {
      Using.resource(c.createStatement()) { s => s.execute("DO RELEASE_LOCK('link-schema-v1')") }
    }
  }
}

object Database {
  @FunctionalInterface trait Connections {
    @throws[SQLException]
    def open(): Connection
  }

  @FunctionalInterface trait Work[T] {
    @throws[Exception]
    def run(connection: Connection): T
  }

  def pooled(url: String, user: String, password: String): Database = {
    val config = new com.zaxxer.hikari.HikariConfig()
    // Velocity uses an isolated plugin class loader; JDBC service discovery
    // on an event thread may otherwise see only the proxy's parent loader.
    val maria = url.startsWith("jdbc:mariadb:")
    if (!maria && !url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Use PostgreSQL or MariaDB JDBC URL")
    config.setDriverClassName(if (maria) "org.mariadb.jdbc.Driver" else "org.postgresql.Driver")
    config.setJdbcUrl(url); config.setUsername(user); config.setPassword(password)
    config.setPoolName("Link-SQL"); config.setMaximumPoolSize(8); config.setMinimumIdle(2)
    config.setConnectionTimeout(5000); config.setValidationTimeout(2000)
    config.addDataSourceProperty("tcpKeepAlive", "true")
    config.addDataSourceProperty("connectTimeout", if (maria) "5000" else "5")
    config.addDataSourceProperty("socketTimeout", if (maria) "15000" else "15")
    val pool = new com.zaxxer.hikari.HikariDataSource(config)
    val db = new Database(() => pool.getConnection())
    db.closeAction = () => pool.close()
    db
  }

  @volatile private var gameThread: java.util.function.BooleanSupplier = () => false
  private val SLOW_GAME_THREAD_NANOS: Long = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)
  private val lastSlowReport = new java.util.concurrent.atomic.AtomicLong(Long.MinValue)
  private val LOG: System.Logger = System.getLogger("Link")

  def gameThreadProbe(probe: java.util.function.BooleanSupplier): Unit = gameThread = Objects.requireNonNull(probe)

  /** Stable transaction lock; collisions serialize unrelated work but cannot corrupt it. */
  @throws[SQLException]
  def lock(c: Connection, key: String): Unit = {
    if (maria(c)) {
      val hash: String =
        try java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))
        catch { case e: java.security.NoSuchAlgorithmException => throw new SQLException(e) }
      update(c, "INSERT INTO link_locks(id) VALUES (?) ON DUPLICATE KEY UPDATE id=VALUES(id)", hash)
      Using.resource(sql(c, "SELECT id FROM link_locks WHERE id=? FOR UPDATE", hash)) { s =>
        Using.resource(s.executeQuery()) { r => if (!r.next()) throw new SQLException("Missing transaction lock") }
      }
    } else {
      Using.resource(c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) { s =>
        s.setString(1, "link:" + key); s.execute()
      }
    }
  }

  /** Concurrent backend work may proceed together; a startup takeover waits for all of it. */
  @throws[SQLException]
  def sharedLock(c: Connection, key: String): Unit = {
    if (maria(c)) {
      val hash: String =
        try java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))
        catch { case e: java.security.NoSuchAlgorithmException => throw new SQLException(e) }
      val exists: Boolean = Using.resource(sql(c, "SELECT id FROM link_locks WHERE id=? LOCK IN SHARE MODE", hash)) { s =>
        Using.resource(s.executeQuery()) { r => r.next() }
      }
      if (!exists) update(c, "INSERT IGNORE INTO link_locks(id) VALUES (?)", hash)
      Using.resource(sql(c, "SELECT id FROM link_locks WHERE id=? LOCK IN SHARE MODE", hash)) { s =>
        Using.resource(s.executeQuery()) { r => if (!r.next()) throw new SQLException("Missing shared lock") }
      }
    } else Using.resource(sql(c, "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))", "link:" + key)) { s => s.execute() }
  }

  @varargs
  @throws[SQLException]
  // Any* (Object... for Java callers): Scala boxes primitives exactly like Java autoboxing did.
  def sql(c: Connection, sql: String, args: Any*): PreparedStatement = {
    val s = c.prepareStatement(if (maria(c)) MariaDialect.translate(sql) else sql)
    try {
      var i = 0
      while (i < args.length) {
        args(i) match {
          case timestamp: Timestamp => s.setTimestamp(i + 1, timestamp, utc())
          case other => s.setObject(i + 1, other.asInstanceOf[AnyRef])
        }
        i += 1
      }
      s
    } catch {
      case e: SQLException => s.close(); throw e
    }
  }

  @varargs
  @throws[SQLException]
  def update(c: Connection, sql: String, args: Any*): Int =
    Using.resource(Database.sql(c, sql, args*)) { s => s.executeUpdate() }

  @throws[SQLException]
  def maria(c: Connection): Boolean = c.getMetaData().getDatabaseProductName().equals("MariaDB")

  private def utc(): java.util.Calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))

  @throws[SQLException]
  def instant(r: ResultSet, column: String): java.time.Instant = r.getTimestamp(column, utc()).toInstant()

  @throws[SQLException]
  def instant(r: ResultSet, column: Int): java.time.Instant = r.getTimestamp(column, utc()).toInstant()
}
