package io.linkmc.core

import io.linkmc.api.LinkApi.{Change, Commit, Event, Session, State, Transaction}
import io.linkmc.core.Database.{instant, lock, sql, update}

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.security.MessageDigest
import java.sql.{Connection, SQLException}
import java.util.{ArrayList, Comparator, List, Optional, UUID}
import scala.util.Using

/** Latest-state storage with atomic multi-aggregate commits. Name retained for binary compatibility. */
final class EventStore(db: Database) {

  @throws[Exception]
  def read(id: String): Optional[State] =
    db.transaction[Optional[State]](c => EventStore.read(c, id))
  @throws[Exception]
  def prefix(prefix: String): List[State] =
    db.transaction[List[State]] { c =>
      val result: List[State] = new ArrayList[State]()
      Using.resource(sql(c, "SELECT id,version,schema_version,payload FROM link_state WHERE starts_with(id,?) ORDER BY id", prefix)) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) result.add(new State(r.getString(1), r.getLong(2), r.getInt(3), r.getBytes(4)))
        }
      }
      List.copyOf(result)
    }
  @throws[Exception]
  def commit(tx: Transaction): Commit =
    db.transaction[Commit](c => commit(c, tx))
  @throws[Exception]
  def commit(tx: Transaction, progress: Runnable): Commit = db.transaction[Commit](c => commit(c, tx, progress))
  @throws[Exception]
  private[core] def commit(c: Connection, tx: Transaction): Commit = commit(c, tx, () => ())
  @throws[Exception]
  private def commit(c: Connection, tx: Transaction, progress: Runnable): Commit = {
    val digest = EventStore.digest(tx)
    lock(c, "operation:" + tx.operation())
    val duplicate: Commit = Using.resource(sql(c, "SELECT digest FROM link_operations WHERE id=?", tx.operation())) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next()) {
          if (!MessageDigest.isEqual(digest, r.getBytes(1))) throw new ConflictException("Operation ID reused with different content")
          new Commit(tx.operation(), true)
        } else null
      }
    }
    if (duplicate != null) return duplicate
    val guards = tx.guards().stream().sorted(Comparator.comparing[Session, String]((s: Session) => s.player().toString())).toList().iterator()
    while (guards.hasNext()) {
      val guard = guards.next()
      SessionStore.requireActive(c, guard)
    }
    val changes = tx.changes().stream().sorted(Comparator.comparing[Change, String]((ch: Change) => ch.id())).toList()
    val checks = changes.iterator()
    while (checks.hasNext()) {
      val change = checks.next()
      // All writers (including NMS adapters) must present the current owner.
      if (change.id().startsWith("player:")) {
        val player = UUID.fromString(change.id().substring(7))
        if (tx.guards().stream().noneMatch((s: Session) => s.player().equals(player)))
          throw new ConflictException("Player change requires a session guard")
      }
      if (change.expectedVersion() == Long.MaxValue) throw new ConflictException("Version exhausted")
    }
    update(c, "INSERT INTO link_operations(id,digest,events_pruned) VALUES (?,?,true)", tx.operation(), digest)
    // Conditional row writes protect versions without an advisory lock per key.
    // Stable ordering prevents opposite-order multi-row writers from deadlocking.
    // Every write and the receipt/outbox remain in this single SQL transaction.
    Using.resource(c.prepareStatement("INSERT INTO link_state(id,version,schema_version,payload) VALUES (?,1,?,?)")) { insert =>
      Using.resource(c.prepareStatement("UPDATE link_state SET version=?,schema_version=?,payload=? WHERE id=? AND version=?")) { replace =>
        val writes = changes.iterator()
        while (writes.hasNext()) {
          val change = writes.next()
          if (change.expectedVersion() == 0) {
            insert.setString(1, change.id()); insert.setInt(2, change.schema()); insert.setBytes(3, change.after())
            try insert.executeUpdate()
            catch {
              case error: SQLException =>
                if ("23505".equals(error.getSQLState()) || error.getErrorCode() == 1062)
                  throw new ConflictException("Version conflict: " + change.id())
                throw error
            }
          } else {
            replace.setLong(1, change.expectedVersion() + 1); replace.setInt(2, change.schema()); replace.setBytes(3, change.after())
            replace.setString(4, change.id()); replace.setLong(5, change.expectedVersion())
            if (replace.executeUpdate() != 1) throw new ConflictException("Version conflict: " + change.id())
          }
          progress.run()
        }
      }
    }
    update(c, "INSERT INTO link_outbox(id) VALUES (?)", tx.operation())
    new Commit(tx.operation(), false)
  }
  /** Legacy, read-only history. New commits do not create events. */
  @Deprecated
  @throws[Exception]
  def history(id: String, after: Long, limit: Int): List[Event] = {
    if (after < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid event page")
    db.transaction[List[Event]] { c =>
      val events: List[Event] = new ArrayList[Event]()
      Using.resource(sql(c, "SELECT operation,version,schema_version,payload,type,created_at FROM link_events WHERE aggregate_id=? AND version>? ORDER BY version LIMIT ?", id, Long.box(after), Int.box(limit))) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) events.add(new Event(r.getObject(1, classOf[UUID]), new State(id, r.getLong(2), r.getInt(3), r.getBytes(4)), r.getString(5), instant(r, 6)))
        }
      }
      List.copyOf(events)
    }
  }
  /** Reads legacy history only; never used for recovery. New revisions have no historical replay. */
  @Deprecated
  @throws[Exception]
  def replay(id: String, version: Long): Optional[State] = {
    if (version < 0) throw new IllegalArgumentException("Invalid revision")
    db.transaction[Optional[State]] { c =>
      Using.resource(sql(c, "SELECT version,schema_version,payload FROM link_events WHERE aggregate_id=? AND version<=? ORDER BY version DESC LIMIT 1", id, Long.box(version))) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) Optional.of(new State(id, r.getLong(1), r.getInt(2), r.getBytes(3))) else Optional.empty[State]()
        }
      }
    }
  }
}

object EventStore {
  @throws[SQLException]
  private[core] def read(c: Connection, id: String): Optional[State] =
    Using.resource(sql(c, "SELECT version,schema_version,payload FROM link_state WHERE id=?", id)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next()) Optional.of(new State(id, r.getLong(1), r.getInt(2), r.getBytes(3))) else Optional.empty[State]()
      }
    }
  @throws[Exception]
  private[core] def digest(tx: Transaction): Array[Byte] = {
    val bytes = new ByteArrayOutputStream()
    Using.resource(new DataOutputStream(bytes)) { out =>
      out.writeInt(tx.guards().size())
      val guards = tx.guards().stream().sorted(Comparator.comparing[Session, String]((g: Session) => g.player().toString())).toList().iterator()
      while (guards.hasNext()) {
        val s = guards.next()
        out.writeUTF(s.player().toString()); out.writeUTF(s.node()); out.writeLong(s.epoch())
      }
      out.writeInt(tx.changes().size())
      val changes = tx.changes().stream().sorted(Comparator.comparing[Change, String]((ch: Change) => ch.id())).toList().iterator()
      while (changes.hasNext()) {
        val c = changes.next()
        out.writeUTF(c.id()); out.writeLong(c.expectedVersion()); out.writeInt(c.schema()); out.writeUTF(c.`type`())
        val payload = c.after(); out.writeInt(payload.length); out.write(payload)
      }
    }
    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
  }
}
