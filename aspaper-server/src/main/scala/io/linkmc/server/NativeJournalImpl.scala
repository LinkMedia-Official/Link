package io.linkmc.server

import io.linkmc.api.LinkApi.{Destination, Session, State, Transaction}
import io.linkmc.core.{ArrivalStore, AsyncSavePipeline, ConflictException, Database, EventStore, FrameCommitter, RouteStore, SaveProgress, SessionStore, TeleportStore}
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit

import java.nio.charset.StandardCharsets
import java.util.{ArrayList, Arrays, Collections, HashMap, HashSet, LinkedHashMap, Objects, Optional, TreeMap, UUID}
import java.util.concurrent.{Callable, CompletableFuture, CompletionException, ConcurrentHashMap, ExecutionException, ExecutorService, Executors, RejectedExecutionException, ScheduledExecutorService, TimeUnit, TimeoutException}

/** In-memory gameplay with bounded asynchronous autosaves and explicit ownership-transfer barriers. */
object NativeJournalImpl {
  @volatile private var instance: Journal = null
  @volatile private var startupLease: Session = null
  private var startupRenewal: ScheduledExecutorService = null

  /** Logs (rate limited, with call site) any SQL transaction that holds the server thread longer than 50 ms. */
  private[server] def watchGameThreadSql(): Unit =
    Database.gameThreadProbe(() => { val server = MinecraftServer.getServer(); server != null && server.isSameThread() })

  /** Runs before Minecraft constructs global clocks, maps and other SavedData. */
  def initializeEarly(): Unit = synchronized {
    val config = LinkConfiguration.get()
    watchGameThreadSql()
    if (!(!config.mode().equals("journal") || enabled())) {
      var db: Database = null
      startupLease = null
      try {
        val node = config.node()
        db = Database.pooled(config.jdbcUrl(), config.username(), config.password()); db.worldStorage(config.worldStorage()); db.migrate()
        val leases = new SessionStore(db, 30)
        startupLease = leases.acquireNode(node)
        db.nodeOwner(() => startupLease)
        initialize(db, node, () => startupLease)
        startupRenewal = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("Link-Startup-Lease").factory())
        startupRenewal.scheduleWithFixedDelay(() => {
          try { startupLease = leases.renew(startupLease) }
          catch { case e: Exception => fenceOwnership(); MinecraftServer.LOGGER.error("Link startup ownership lost", e) }
        }, 5L, 5L, TimeUnit.SECONDS)
      } catch {
        case e: Exception =>
          finishStartup()
          val journal = instance; instance = null
          if (journal != null) try journal.savePipeline.close() catch { case cleanup: Exception => e.addSuppressed(cleanup) }
          if (db != null) {
            try { if (startupLease != null) new SessionStore(db, 30).releaseNode(startupLease) } catch { case cleanup: Exception => e.addSuppressed(cleanup) }
            db.close()
          }
          throw new IllegalStateException("Cannot initialize durable native storage", e)
      }
    }
  }

  private[server] def startupOwner(): Session = startupLease
  private[server] def database(): Database = instance.db
  private[server] def finishStartup(): Unit = { if (startupRenewal != null) { startupRenewal.shutdownNow(); startupRenewal = null } }

  private final case class PendingRemote(route: RouteStore.Request, owner: Session)
  private val RETRY_NANOS: Long = TimeUnit.SECONDS.toNanos(5)

  @throws[Exception]
  private[server] def initialize(db: Database, node: String, lease: java.util.function.Supplier[Session]): Unit = { instance = new Journal(db, node, lease) }
  def enabled(): Boolean = instance != null
  private[server] def fenceOwnership(): Unit = { val j = instance; if (j != null) j.poisoned = true }
  def moving(player: UUID): Boolean = { val j = instance; j != null && (j.moving.contains(player) || j.freezing.contains(player)) }
  def runTask(task: java.util.function.BooleanSupplier): Boolean = {
    val result = Array[Boolean](false); run(() => result(0) = task.getAsBoolean()); result(0)
  }
  def run(action: Runnable): Unit = {
    val journal = instance
    if (journal == null || !journal.active) { action.run(); return }
    if (!Bukkit.isPrimaryThread()) {
      if (MinecraftServer.getServer().hasStopped()) return
      throw new IllegalStateException("Native frame must run on the server thread")
    }
    // Shutdown drains scheduled tasks. Once fenced, discard their actions rather
    // than throwing from the drain and preventing the server's cleanup from running.
    if (journal.poisoned) return
    val outer = journal.depth == 0
    journal.depth += 1
    try {
      if (outer) journal.finishSaveSafely()
      // Gameplay/plugin exceptions propagate unchanged and are handled exactly like vanilla Paper
      // (packet -> disconnect, task -> logged). They never stop the backend.
      action.run()
      if (outer) journal.checkpointSafely()
    } catch {
      case fatal: FatalJournalException =>
        journal.fail(fatal.getCause)
        throw new IllegalStateException("Link ownership lost", fatal.getCause)
    } finally { journal.depth -= 1 }
  }

  /** Only ownership loss / journal version conflicts stop the backend: another writer may own the state. */
  private final class FatalJournalException(cause: Throwable) extends RuntimeException(cause)

  /**
   * Ownership loss only. Ordinary {@link ConflictException}s (unknown warp, expired route, world exists...) are
   * business outcomes and never stop the backend. Journal version conflicts are detected via {@code frames.failed()}.
   */
  private[server] def fatal(error: Throwable): Boolean = {
    var t = error
    var found = false
    while (!found && t != null) {
      t match {
        case _: FatalJournalException => found = true
        case c: ConflictException if c.ownershipLost() => found = true
        case _ =>
      }
      if (!found) t = t.getCause
    }
    found
  }

  private def unwrap(error0: Throwable): Throwable = {
    var error = error0
    while ((error.isInstanceOf[ExecutionException] || error.isInstanceOf[CompletionException]) && error.getCause != null) error = error.getCause
    error
  }

  /** Runs on the server thread once no checkpoint is in flight, without waiting for storage. */
  private[server] def whenIdle(task: Runnable): Unit = {
    val j = instance
    if (j == null || !j.active || j.saving == null) { task.run(); return }
    j.afterSave.add(task)
  }

  private trait SqlAction { @throws[Exception] def run(): Unit }

  /** Explicit save / ownership hand-off barrier. Ordinary ticks never wait for storage. */
  @throws[Exception]
  private[server] def flush(): Unit = {
    val j = instance; if (j == null) return
    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Capture must run on the server thread")
    j.finishSave(true); j.nextSaveNanos = 0; j.checkpoint(); j.finishSave(true)
  }

  def saveAsync(): CompletableFuture[Void] = {
    val future = new CompletableFuture[Void]()
    if (!watch(future)) return future
    run(() => complete(future, null))
    future
  }

  private[server] def complete[T](future: CompletableFuture[T], value: T): Unit = {
    val j = instance
    if (j != null && j.active && j.depth > 0) { j.completions.add(() => future.complete(value)); j.failures.add(e => future.completeExceptionally(e)) }
    else future.complete(value)
  }

  private[server] def watch(future: CompletableFuture[?]): Boolean = {
    val j = instance; if (j == null) return true
    j.requests.add(future); future.whenComplete((v, e) => j.requests.remove(future))
    if (j.poisoned) { future.completeExceptionally(new IllegalStateException("Link journal fenced")); return false }
    true
  }

  private[server] def activate(): Unit = {
    if (instance.poisoned) throw new IllegalStateException("Startup lease was lost")
    instance.active = true
    run(() => { try flush() catch { case e: Exception => throw new IllegalStateException(e) } })
  }

  // ── Archive of worlds checkpointed by older builds (read once by LinkWorldExport) ─────────────────────
  private[server] final case class Archive(node: String, states: java.util.Map[String, Array[Byte]], catalog: java.util.Map[String, java.lang.Byte], blobs: io.linkmc.core.WorldBlobs)
  /** Null once the vanilla export has been committed: nothing to export and nothing was loaded. */
  private[server] def archive(): Archive = {
    val j = instance
    if (j == null || j.exported) null else Archive(j.node, java.util.Collections.unmodifiableMap(j.restored), java.util.Collections.unmodifiableMap(j.catalog), j.db.worldBlobs())
  }
  /** Commits the one-time export marker and drops the archived world data from memory. Server thread, startup. */
  @throws[Exception]
  private[server] def markExported(summary: String): Unit = {
    val j = instance
    j.frames.commit(java.util.List.of(j.nodeLease.get()), java.util.Map.of(j.exportKey, summary.getBytes(StandardCharsets.UTF_8)), () => ())
    j.exported = true; j.restored.clear(); j.catalog.clear()
  }

  /** Starts the SQL arrival claim and player-data read in the background (called from PrepareSpawnTask's constructor). */
  def prefetchArrival(player: UUID, connection: Connection): Unit = {
    val j = instance; if (j == null || (j.connections.get(player) eq connection)) return
    try { j.arrivalClaims.computeIfAbsent(connection, c => CompletableFuture.runAsync(() => j.claimArrival(player, connection), j.arrivalWorkers)) }
    catch { case closed: RejectedExecutionException => /* shutting down: prepare() falls back to the direct path */ }
  }
  /** True once {@link #prepare} can run without waiting for the database. */
  def arrivalReady(player: UUID, connection: Connection): Boolean = {
    val j = instance; if (j == null) return true
    val claim = j.arrivalClaims.get(connection)
    claim == null || claim.isDone()
  }
  def prepare(player: UUID, connection: Connection): Unit = {
    if (!enabled()) return
    val j = instance
    val claim = j.arrivalClaims.remove(connection)
    if (claim != null) {
      try { claim.join(); return }
      catch {
        case failed: CompletionException =>
          throw (failed.getCause match {
            case r: RuntimeException => r
            case _ => new IllegalStateException("Link arrival rejected", failed.getCause)
          })
      }
    }
    j.claimArrival(player, connection)
  }

  /** Bukkit OfflinePlayer/pre-login reads see committed data without claiming a live session. */
  def readPlayerData(id: UUID): Optional[CompoundTag] = {
    val journal = instance; if (journal == null) return Optional.empty()
    try {
      val state = journal.store.read("player:" + id)
      if (state.isEmpty()) Optional.empty() else Optional.of(NativePlayerCodec.decode(state.get().payload()))
    } catch { case error: Exception => throw new IllegalStateException("Cannot read committed player data", error) }
  }
  def playerData(id: UUID): Optional[CompoundTag] = {
    if (!instance.admitted.containsKey(id)) throw new IllegalStateException("Player data requested before arrival claim")
    Optional.ofNullable(instance.playerData.get(id)).map[CompoundTag](tag => tag.copy())
  }
  /** Explicit cross-server destination, or null for a fresh join (vanilla placement from the saved player data). */
  def destination(id: UUID): Destination = { val arrival = Objects.requireNonNull(instance.admitted.get(id), "Missing arrival"); if (arrival.fixed) arrival.destination else null }
  def joined(player: ServerPlayer): Unit = {
    if (!enabled()) return
    val j = instance; j.players.put(player.getUUID(), player)
    val uuid = player.getUUID(); val name = player.getBukkitEntity().getName()
    j.offThread("player directory registration", () => new TeleportStore(j.db).registerPlayer(uuid, name), null)
    val tag = j.playerData.get(player.getUUID()); if (tag != null) NativePlayerCodec.recover(player, tag)
    LinkUtilities.restoreFlight(player.getBukkitEntity())
  }
  def left(player: ServerPlayer): Unit = { if (enabled()) instance.departing.add(player.getUUID()) }
  def stats(id: UUID): com.google.gson.JsonElement = {
    if (!enabled()) return null
    val tag = instance.playerData.get(id)
    if (tag == null) null else tag.getString("LinkStats").map[com.google.gson.JsonElement](s => com.google.gson.JsonParser.parseString(s)).orElse(null)
  }
  def advancements(id: UUID): com.google.gson.JsonElement = {
    if (!enabled()) return null
    val tag = instance.playerData.get(id)
    if (tag == null) null else tag.getString("LinkAdvancements").map[com.google.gson.JsonElement](s => com.google.gson.JsonParser.parseString(s)).orElse(null)
  }

  @throws[Exception]
  private[server] def renew(): Unit = {
    if (!enabled() || instance.poisoned) return
    val j = instance
    val it = java.util.List.copyOf(j.admitted.entrySet()).iterator()
    while (it.hasNext) {
      val entry = it.next()
      if (j.moving.contains(entry.getKey)) {
        val source = j.connections.get(entry.getKey)
        if (source != null && source.isConnected()) j.sessions.renewMoving(entry.getValue.session)
      } else {
        val old = entry.getValue
        if (j.freezing.contains(entry.getKey)) {
          // Hand-off SQL may have just switched this session to MOVING; either state is expected here.
          try { val renewed = j.sessions.renew(old.session); j.admitted.replace(entry.getKey, old, old.copy(session = renewed)) }
          catch { case notActive: ConflictException => try { j.sessions.renewMoving(old.session); () } catch { case ignored: ConflictException => } }
        } else {
          try {
            val connection = j.connections.get(entry.getKey)
            if (connection != null && !connection.isConnected() && !j.players.containsKey(entry.getKey)) {
              if (j.admitted.remove(entry.getKey, old)) { j.connections.remove(entry.getKey, connection); j.playerData.remove(entry.getKey); j.sessions.disconnect(old.session) }
            } else {
              val renewed = j.sessions.renew(old.session)
              j.admitted.replace(entry.getKey, old, old.copy(session = renewed))
            }
          } catch { case stale: ConflictException => if ((j.admitted.get(entry.getKey) eq old) && !j.moving.contains(entry.getKey)) throw stale }
        }
      }
    }
    // Claims whose connection closed before the spawn task consumed them; the admission itself is released above.
    j.arrivalClaims.entrySet().removeIf(e => e.getValue.isDone() && !e.getKey.isConnected())
    val routes = new RouteStore(j.db).pending(j.node).iterator()
    while (routes.hasNext) submitRoute(routes.next())
  }

  private[server] def submitRoute(route: RouteStore.Request): Unit = {
    val j = instance
    if (j == null || j.poisoned || LinkRuntimeImpl.isClosing() || !j.queuedRoutes.add(route.id)) return
    try j.routeWorkers.execute(() => j.claimRoute(route))
    catch { case error: RuntimeException => j.queuedRoutes.remove(route.id); throw error }
  }

  private[server] def shutdown(): Unit = {
    finishStartup()
    val j = instance; if (j == null) return
    try {
      if (j.poisoned) throw new IllegalStateException("Backend ownership or checkpoint failed")
      if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Final checkpoint requires the server thread")
      run(() => { try flush() catch { case e: Exception => throw new IllegalStateException(e) } })
    } finally {
      j.active = false; LinkRuntimeImpl.fence()
      j.requests.forEach(f => f.completeExceptionally(new IllegalStateException("Backend stopped"))); j.requests.clear()
      j.arrivalWorkers.shutdownNow(); j.routeWorkers.shutdownNow()
      j.background.shutdown()
      try { if (!j.background.awaitTermination(10, TimeUnit.SECONDS)) MinecraftServer.LOGGER.warn("Link background acknowledgements did not finish before shutdown") }
      catch { case e: InterruptedException => Thread.currentThread().interrupt() }
      try j.savePipeline.close() finally instance = null
    }
  }

  /** Per-instance state of the original NativeJournal. */
  private final class Journal(val db: Database, val node: String, val nodeLease: java.util.function.Supplier[Session]) {
    private[NativeJournalImpl] val savePipeline: AsyncSavePipeline = new AsyncSavePipeline(LinkConfiguration.get().saveWorkers())
    private[NativeJournalImpl] val saveIntervalNanos: Long = TimeUnit.SECONDS.toNanos(LinkConfiguration.get().saveIntervalSeconds())
    private[NativeJournalImpl] var nextSaveNanos: Long = 0L
    private[NativeJournalImpl] val saveProgress: SaveProgress = new SaveProgress()
    private[NativeJournalImpl] var saving: CompletableFuture[java.util.Map[String, Array[Byte]]] = null
    private[NativeJournalImpl] var savedCompletions: java.util.List[Runnable] = java.util.List.of()
    private[NativeJournalImpl] var savedFailures: java.util.List[java.util.function.Consumer[Throwable]] = java.util.List.of()
    private[NativeJournalImpl] var savedDepartures: java.util.Set[UUID] = java.util.Set.of()
    private[NativeJournalImpl] val restored: java.util.Map[String, Array[Byte]] = new HashMap()
    private[NativeJournalImpl] val catalog: java.util.Map[String, java.lang.Byte] = new HashMap()
    private[NativeJournalImpl] val admitted: java.util.Map[UUID, ArrivalStore.Arrival] = new ConcurrentHashMap()
    private[NativeJournalImpl] val connections: java.util.Map[UUID, Connection] = new ConcurrentHashMap()
    private[NativeJournalImpl] val playerData: java.util.Map[UUID, CompoundTag] = new ConcurrentHashMap()
    private[NativeJournalImpl] val players: java.util.Map[UUID, ServerPlayer] = new ConcurrentHashMap()
    private[NativeJournalImpl] val departing: java.util.Set[UUID] = new HashSet()
    private[NativeJournalImpl] val localTravel: java.util.Set[UUID] = ConcurrentHashMap.newKeySet()
    private[NativeJournalImpl] val moving: java.util.Set[UUID] = ConcurrentHashMap.newKeySet()
    /** Remote travellers frozen at capture time while their source checkpoint commits. Still renewed as ACTIVE. */
    private[NativeJournalImpl] val freezing: java.util.Set[UUID] = ConcurrentHashMap.newKeySet()
    /** Remote routes claimed on the server thread, waiting for the next checkpoint to capture and freeze them. */
    private[NativeJournalImpl] val remoteRequests: java.util.Map[UUID, PendingRemote] = new LinkedHashMap()
    private[NativeJournalImpl] var savedRemote: java.util.List[PendingRemote] = java.util.List.of()
    /** Server-thread work that must observe the latest committed state; runs as soon as no checkpoint is in flight. */
    private[NativeJournalImpl] val afterSave: java.util.List[Runnable] = new ArrayList()
    private[NativeJournalImpl] var retryNotBefore: Long = 0L
    private[NativeJournalImpl] var storageDegraded: Boolean = false
    /** Off-thread SQL for acknowledgements that must not stall the tick. A single thread keeps their order. */
    private[NativeJournalImpl] val background: ExecutorService = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Link-Journal-IO").factory())
    private[NativeJournalImpl] val queuedRoutes: java.util.Set[UUID] = ConcurrentHashMap.newKeySet()
    private[NativeJournalImpl] val completions: java.util.List[Runnable] = new ArrayList()
    private[NativeJournalImpl] val failures: java.util.List[java.util.function.Consumer[Throwable]] = new ArrayList()
    private[NativeJournalImpl] val requests: java.util.Set[CompletableFuture[?]] = ConcurrentHashMap.newKeySet()
    private[NativeJournalImpl] var depth: Int = 0
    @volatile private[NativeJournalImpl] var poisoned: Boolean = false
    @volatile private[NativeJournalImpl] var active: Boolean = false
    private[NativeJournalImpl] var lastCaptureReport: Long = 0L
    /** Arrival claims started off the server thread when a player enters configuration, keyed by connection. */
    private[NativeJournalImpl] val arrivalClaims: java.util.Map[Connection, CompletableFuture[Void]] = new ConcurrentHashMap()
    private[NativeJournalImpl] val arrivalWorkers: ExecutorService = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("Link-Arrival-", 0L).factory())
    /** Route status/claim SQL runs on these threads; the server thread only does the in-game part. */
    private[NativeJournalImpl] val routeWorkers: ExecutorService = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("Link-Route-", 0L).factory())

    // Constructor body (Java: runs after the field initializers above).
    private[NativeJournalImpl] val store: EventStore = new EventStore(db)
    private[NativeJournalImpl] val frames: FrameCommitter = new FrameCommitter((tx: Transaction) => { saveProgress.phase("sql", tx.changes().size()); store.commit(tx, () => saveProgress.advance()) })
    private[NativeJournalImpl] val sessions: SessionStore = new SessionStore(db, 30)
    private[NativeJournalImpl] val arrivals: ArrivalStore = new ArrivalStore(db)
    /** Committed once the worlds an older build checkpointed into the database were exported to vanilla files. */
    private[NativeJournalImpl] val exportKey: String = "native:" + node + "/vanilla-export"
    @volatile private[NativeJournalImpl] var exported: Boolean = false
    locally {
      val marker = store.read(exportKey)
      if (marker.isPresent()) { frames.restore(marker.get()); exported = true }
      else {
        // Worlds are vanilla files now; this archive is only read by the one-time export, then dropped.
        val states = store.prefix("native:" + node + "/").iterator()
        while (states.hasNext) { val state = states.next(); frames.restore(state); restored.put(state.id(), state.payload()) }
        val prefix = "native:" + node + "/catalog/"
        restored.forEach((key, value) => { if (key.startsWith(prefix) && value.length == 1) catalog.put(key.substring(prefix.length()), java.lang.Byte.valueOf(value(0))) })
      }
      MinecraftServer.LOGGER.info("Link asynchronous storage: interval={}s, workers={}, maximum in-flight checkpoints=1", LinkConfiguration.get().saveIntervalSeconds(), LinkConfiguration.get().saveWorkers())
    }

    private[NativeJournalImpl] def fail(error: Throwable): Unit = {
      if (poisoned) return
      poisoned = true; LinkPacketGate.abort(error); LinkRuntimeImpl.fence(); finishStartup()
      requests.forEach(f => f.completeExceptionally(error)); requests.clear()
      failures.forEach(f => f.accept(error)); savedFailures.forEach(f => f.accept(error)); failures.clear(); completions.clear()
      MinecraftServer.LOGGER.error("Link lost backend ownership (another instance or an expired lease owns this state); stopping to avoid two writers", error)
      MinecraftServer.getServer().halt(false)
    }

    private[NativeJournalImpl] def finishSaveSafely(): Unit = {
      try finishSave(false)
      catch {
        case fatal: FatalJournalException => throw fatal
        case error: Exception =>
          if (NativeJournalImpl.fatal(error) || frames.failed()) throw new FatalJournalException(error)
          MinecraftServer.LOGGER.error("Link save completion failed; gameplay continues", error)
      }
    }

    private[NativeJournalImpl] def checkpointSafely(): Unit = {
      if (System.nanoTime() < retryNotBefore) return
      try checkpoint()
      catch {
        case fatal: FatalJournalException => throw fatal
        case error: Exception =>
          if (NativeJournalImpl.fatal(error) || frames.failed()) throw new FatalJournalException(error)
          retryNotBefore = System.nanoTime() + RETRY_NANOS
          if (!storageDegraded || saveProgress.reportDue()) MinecraftServer.LOGGER.error("Link could not start a checkpoint; gameplay continues and it is retried in 5s", error)
          storageDegraded = true
      }
    }

    @throws[Exception]
    private[NativeJournalImpl] def checkpoint(): Unit = {
      finishSave(false)
      if (saving != null) return
      if (completions.isEmpty() && departing.isEmpty() && remoteRequests.isEmpty() && System.nanoTime() < nextSaveNanos) return
      val captureStarted = System.nanoTime()
      val tags: java.util.Map[String, CompoundTag] = new TreeMap()
      val owners: java.util.List[Session] = new ArrayList(); owners.add(nodeLease.get())
      val snapshots: java.util.Map[String, Array[Byte]] = new TreeMap()
      // Worlds and their saved data are vanilla files (autosave / save-all). A checkpoint holds player state only.
      val playerIt = players.entrySet().iterator()
      while (playerIt.hasNext) {
        val entry = playerIt.next()
        // Frozen travellers (hand-off SQL in flight) were committed by the checkpoint that froze them.
        if (!(moving.contains(entry.getKey) || freezing.contains(entry.getKey))) {
          val admission = admitted.get(entry.getKey); if (admission == null) throw new IllegalStateException("Player without DB owner")
          owners.add(admission.session)
          tags.put("player:" + entry.getKey, NativePlayerCodec.capture(entry.getValue))
        }
      }
      // Remote travellers are captured above and frozen in this same server-thread step, so the committed
      // state is exactly what the destination loads. Their packets/ticks are ignored until the hand-off.
      val freezingNow: java.util.List[PendingRemote] = new ArrayList()
      val remoteIt = remoteRequests.values().iterator()
      while (remoteIt.hasNext) {
        val pending = remoteIt.next()
        val id = pending.route.reservation.player()
        if (players.containsKey(id) && !departing.contains(id)) freezingNow.add(pending)
        else abortRemote(pending, null)
      }
      remoteRequests.clear()
      val freezeIt = freezingNow.iterator()
      while (freezeIt.hasNext) freezing.add(freezeIt.next().route.reservation.player())
      savedRemote = java.util.List.copyOf(freezingNow)
      savedCompletions = java.util.List.copyOf(completions); completions.clear()
      savedFailures = java.util.List.copyOf(failures); failures.clear()
      savedDepartures = java.util.Set.copyOf(departing)
      // Keep departing players registered until commit so heartbeat renewal cannot release ownership early.
      departing.removeAll(savedDepartures)
      nextSaveNanos = System.nanoTime() + saveIntervalNanos
      reportCapture(captureStarted, tags.size())
      saveProgress.begin()
      try {
        saving = savePipeline.submit[java.util.Map[String, Array[Byte]]](() => {
          val compression: java.util.List[Callable[java.util.Map.Entry[String, Array[Byte]]]] = new ArrayList()
          tags.forEach((key, tag) => { compression.add(() => java.util.Map.entry(key, NativePlayerCodec.compress(tag))); () })
          saveProgress.phase("player-data", compression.size())
          val values = savePipeline.parallel[java.util.Map.Entry[String, Array[Byte]]](compression, () => saveProgress.advance()).iterator()
          while (values.hasNext) { val value = values.next(); snapshots.put(value.getKey, value.getValue) }
          frames.commit(owners, snapshots, () => ())
          snapshots
        })
      } catch { case rejected: RuntimeException => storageFailed(rejected); throw rejected }
    }

    /** Server-thread capture time is the one remaining periodic cost of a checkpoint; make it visible. */
    private[NativeJournalImpl] def reportCapture(started: Long, tags: Int): Unit = {
      val now = System.nanoTime(); val millis = TimeUnit.NANOSECONDS.toMillis(now - started)
      if (millis < java.lang.Long.getLong("link.captureWarnMillis", 50L).longValue()) return
      if (now - lastCaptureReport < TimeUnit.SECONDS.toNanos(60) && lastCaptureReport != 0) return
      lastCaptureReport = now
      MinecraftServer.LOGGER.warn("Link checkpoint capture held the server thread for {} ms ({} players)", millis, tags)
    }

    /** Completion runs on the server thread; workers never invoke Bukkit/plugin callbacks. */
    @throws[Exception]
    private[NativeJournalImpl] def finishSave(wait: Boolean): Unit = {
      if (saving == null) { runAfterSave(); return }
      if (!wait && !saving.isDone()) {
        // Never stop or block the tick for slow storage. Gameplay continues; travel waits for this save.
        try {
          saveProgress.check(LinkConfiguration.get().saveStallSeconds(), LinkConfiguration.get().saveTimeoutSeconds())
          if (saveProgress.reportDue()) MinecraftServer.LOGGER.info("Link save in progress: {}", saveProgress.status())
        } catch {
          case slow: TimeoutException =>
            if (saveProgress.reportDue()) MinecraftServer.LOGGER.warn("Link save is slow; gameplay continues and cross-server travel waits: {}", saveProgress.status())
        }
        return
      }
      val result: java.util.Map[String, Array[Byte]] =
        try {
          if (wait) saveProgress.await(saving, LinkConfiguration.get().saveStallSeconds(), LinkConfiguration.get().saveTimeoutSeconds(), (status: String) => MinecraftServer.LOGGER.info("Link save in progress: {}", status))
          else saving.join()
        } catch {
          case error: Exception =>
            if (!saving.isDone()) throw error // explicit barrier deadline (shutdown): the checkpoint is still running
            saving = null
            val cause = unwrap(error)
            if (NativeJournalImpl.fatal(cause) || frames.failed()) throw new FatalJournalException(cause)
            storageFailed(cause)
            runAfterSave()
            if (wait) throw new IllegalStateException("Link checkpoint failed", cause)
            return
        }
      if (wait || storageDegraded) MinecraftServer.LOGGER.info("Link save completed: {}", saveProgress.status())
      storageDegraded = false; retryNotBefore = 0
      saving = null
      val completed = savedCompletions; savedCompletions = java.util.List.of(); savedFailures = java.util.List.of()
      val completedIt = completed.iterator()
      while (completedIt.hasNext) {
        val completion = completedIt.next()
        try completion.run()
        catch {
          case error: RuntimeException =>
            if (NativeJournalImpl.fatal(error)) throw new FatalJournalException(error)
            MinecraftServer.LOGGER.error("Link post-save completion failed", error)
        }
      }
      val departuresIt = savedDepartures.iterator()
      while (departuresIt.hasNext) {
        val id = departuresIt.next()
        val admission = admitted.remove(id)
        players.remove(id); connections.remove(id); playerData.remove(id); moving.remove(id); freezing.remove(id); departing.remove(id)
        // Publish RELEASED only after this backend has forgotten the old connection.
        if (admission != null) offThread("session release", () => sessions.disconnect(admission.session), null)
      }
      savedDepartures = java.util.Set.of()
      val frozen = savedRemote; savedRemote = java.util.List.of()
      val frozenIt = frozen.iterator()
      while (frozenIt.hasNext) {
        val pending = frozenIt.next()
        try handOff(pending)
        catch {
          case error: RuntimeException =>
            if (NativeJournalImpl.fatal(error)) throw (error match { case f: FatalJournalException => f; case _ => new FatalJournalException(error) })
            // One failed hand-off must never leave this or any other traveller frozen.
            MinecraftServer.LOGGER.error("Link remote travel hand-off failed", error)
            moving.remove(pending.route.reservation.player())
            abortRemote(pending, "일시적인 오류로 서버 이동을 취소했습니다. 다시 시도하세요.")
        }
      }
      runAfterSave()
    }

    /** The checkpoint did not commit. Everything that waited for it is re-queued for the next attempt. */
    private[NativeJournalImpl] def storageFailed(cause: Throwable): Unit = {
      val first = !storageDegraded; storageDegraded = true; retryNotBefore = System.nanoTime() + RETRY_NANOS
      if (first || saveProgress.reportDue()) MinecraftServer.LOGGER.error("Link checkpoint failed; gameplay continues and the save is retried every 5s. Cross-server travel waits for a successful save.", cause)
      val c: java.util.List[Runnable] = new ArrayList(savedCompletions); c.addAll(completions); completions.clear(); completions.addAll(c)
      val f: java.util.List[java.util.function.Consumer[Throwable]] = new ArrayList(savedFailures); f.addAll(failures); failures.clear(); failures.addAll(f)
      savedCompletions = java.util.List.of(); savedFailures = java.util.List.of()
      departing.addAll(savedDepartures); savedDepartures = java.util.Set.of()
      val it = savedRemote.iterator()
      while (it.hasNext) abortRemote(it.next(), "저장에 실패해 서버 이동을 취소했습니다. 잠시 후 다시 시도하세요.")
      savedRemote = java.util.List.of()
    }

    private[NativeJournalImpl] def runAfterSave(): Unit = {
      if (afterSave.isEmpty()) return
      val tasks = java.util.List.copyOf(afterSave); afterSave.clear()
      val it = tasks.iterator()
      while (it.hasNext) {
        val task = it.next()
        try task.run()
        catch {
          case fatal: FatalJournalException => throw fatal
          case error: RuntimeException => MinecraftServer.LOGGER.error("Link deferred task failed", error)
        }
      }
    }

    /** SQL that must not stall the tick. Retries transient failures; onGiveUp runs on the server thread. */
    private[NativeJournalImpl] def offThread(what: String, action: SqlAction, onGiveUp: Runnable): Unit = {
      try {
        background.execute(() => {
          var last: Exception = null
          var succeeded = false
          var stop = false
          var attempt = 0
          while (!succeeded && !stop && attempt < 6 && !poisoned) {
            try { action.run(); succeeded = true }
            catch {
              case error: Exception =>
                last = error
                if (NativeJournalImpl.fatal(error)) stop = true
                else {
                  try Thread.sleep(500L * (attempt + 1))
                  catch { case interrupted: InterruptedException => Thread.currentThread().interrupt(); stop = true }
                }
            }
            if (!succeeded && !stop) attempt += 1
          }
          if (!succeeded) {
            if (last != null) MinecraftServer.LOGGER.error("Link {} failed", what, last)
            if (onGiveUp != null && !poisoned) MinecraftServer.getServer().execute(onGiveUp)
          }
        })
      } catch { case closed: RejectedExecutionException => MinecraftServer.LOGGER.warn("Link {} skipped during shutdown", what) }
    }

    private[NativeJournalImpl] def abortRemote(pending: PendingRemote, message: String): Unit = {
      val id = pending.route.reservation.player()
      freezing.remove(id)
      offThread("route rejection", () => new RouteStore(db).reject(pending.route), null)
      val player = players.get(id)
      if (message != null && player != null) player.getBukkitEntity().sendMessage(message)
    }

    /** Source state is durable: prepare the hand-off (same order as before, but the tick never waited for storage). */
    private[NativeJournalImpl] def handOff(pending: PendingRemote): Unit = {
      val id = pending.route.reservation.player()
      val player = players.get(id)
      if (player == null || departing.contains(id) || !freezing.contains(id)) { abortRemote(pending, null); return }
      val routes = new RouteStore(db)
      // The player stays frozen (and is skipped by later captures, its state is already committed) while the
      // ownership hand-off SQL runs off the server thread.
      try {
        routeWorkers.execute(() => {
          var failure: Exception = null
          try routes.prepareRemote(pending.route, pending.owner) catch { case e: Exception => failure = e }
          val result = failure
          MinecraftServer.getServer().execute(() => { if (!poisoned) NativeJournalImpl.run(() => completeHandOff(pending, routes, result)) })
        })
      } catch { case closed: RejectedExecutionException => abortRemote(pending, null) }
    }

    private[NativeJournalImpl] def completeHandOff(pending: PendingRemote, routes: RouteStore, failure: Exception): Unit = {
      val id = pending.route.reservation.player()
      if (failure != null) {
        if (NativeJournalImpl.fatal(failure)) throw new FatalJournalException(failure)
        val expired = failure.isInstanceOf[ConflictException]
        if (!expired) MinecraftServer.LOGGER.warn("Link remote travel preparation failed for {}", id, failure)
        abortRemote(pending, if (expired) "서버 이동 요청이 만료되었습니다. 다시 시도하세요." else "일시적인 오류로 서버 이동을 취소했습니다. 다시 시도하세요.")
        return
      }
      // Ownership is now MOVING: never capture this player again on this backend.
      moving.add(id); freezing.remove(id)
      val player = players.get(id)
      if (player != null && !player.isRemoved()) {
        try player.level().removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER)
        catch {
          case error: RuntimeException =>
            MinecraftServer.LOGGER.error("Link could not detach travelling player {}; disconnecting so it reconnects from committed state", id, error)
            player.connection.disconnect(net.kyori.adventure.text.Component.text("서버 이동 처리 중 오류가 발생했습니다. 다시 접속하세요."))
        }
      }
      nextSaveNanos = 0
      // READY follows the durable source removal checkpoint. Only Velocity opens the new connection.
      completions.add(() => offThread("remote route acknowledgement", () => routes.ready(pending.route.id), () => {
        // READY could not be published: drop the frozen connection so the player reconnects from committed state.
        val stuck = players.get(id)
        if (stuck != null) stuck.connection.disconnect(net.kyori.adventure.text.Component.text("서버 이동 처리 중 오류가 발생했습니다. 다시 접속하세요."))
      }))
    }

    private[NativeJournalImpl] def worldKey(name: String): String = "native:" + node + "/world/" + name

    private[NativeJournalImpl] def claimArrival(player: UUID, connection: Connection): Unit = {
      val j = this
      val previous = j.connections.putIfAbsent(player, connection)
      if (previous eq connection) return
      if (previous != null) throw new IllegalStateException("Duplicate player connection")
      try {
        val arrival = j.arrivals.claim(player, j.node); j.admitted.put(player, arrival)
        val saved: Optional[State] = j.store.read("player:" + player)
        if (saved.isPresent()) { j.frames.restore(saved.get()); j.playerData.put(player, NativePlayerCodec.decode(saved.get().payload())) }
      } catch {
        case e: Exception =>
          val arrival = j.admitted.remove(player); j.connections.remove(player, connection); j.playerData.remove(player)
          if (arrival != null) try j.sessions.disconnect(arrival.session) catch { case cleanup: Exception => e.addSuppressed(cleanup) }
          throw new IllegalStateException("Link arrival rejected", e)
      }
    }

    /** Off the server thread: validate and durably claim the route, then continue on the server thread. */
    private[NativeJournalImpl] def claimRoute(route: RouteStore.Request): Unit = {
      var handedOver = false
      val id = route.reservation.player()
      try {
        val admission = admitted.get(id)
        if (admission == null || !players.containsKey(id) || poisoned) return // not playing here (yet): retried by the heartbeat
        val owner = admission.session
        val routes = new RouteStore(db)
        if (!routes.status(route.id).equals("REQUESTED")) return
        try { if (!routes.claim(route, owner)) return }
        catch {
          case rejected: ConflictException =>
            if (NativeJournalImpl.fatal(rejected)) throw rejected
            routes.reject(route)
            MinecraftServer.getServer().execute(() => message(id, "이동 요청이 만료되었거나 이전 접속의 요청입니다. 다시 시도하세요."))
            return
        }
        MinecraftServer.getServer().execute(() => {
          try {
            if (!poisoned) NativeJournalImpl.run(() => transfer(route, owner))
          } finally { queuedRoutes.remove(route.id) }
        })
        handedOver = true
      } catch {
        case error: Exception =>
          if (!poisoned) MinecraftServer.LOGGER.warn("Link travel request {} deferred; retried by the next heartbeat", route.id, error)
      } finally { if (!handedOver) queuedRoutes.remove(route.id) }
    }

    private[NativeJournalImpl] def message(player: UUID, text: String): Unit = { val p = players.get(player); if (p != null) p.getBukkitEntity().sendMessage(text) }

    /** Server thread, route already claimed: every exit either performs the travel or rejects the route. */
    private[NativeJournalImpl] def transfer(route: RouteStore.Request, owner: Session): Unit = {
      val id = route.reservation.player()
      val routes = new RouteStore(db)
      val player = players.get(id)
      if (player == null) { offThread("route rejection", () => routes.reject(route), null); return }
      if (moving.contains(id) || freezing.contains(id) || remoteRequests.containsKey(id) || localTravel.contains(id)) {
        offThread("route rejection", () => routes.reject(route), null)
        player.getBukkitEntity().sendMessage("이미 이동 중입니다. 잠시 후 다시 시도하세요."); return
      }
      val local = route.reservation.destination().node().equals(node)
      try {
        val d = route.reservation.destination(); val world: org.bukkit.World = if (local) Bukkit.getWorld(d.world()) else null
        if (local && world == null) { offThread("route rejection", () => routes.reject(route), null); player.getBukkitEntity().sendMessage("이동할 월드가 로드되지 않았습니다."); return }
        player.closeContainer(); player.stopRiding()
        if (local) {
          localTravel.add(id)
          if (!player.getBukkitEntity().teleport(new org.bukkit.Location(world, d.x(), d.y(), d.z(), d.yaw(), d.pitch()))) {
            localTravel.remove(id); offThread("route rejection", () => routes.reject(route), null); player.getBukkitEntity().sendMessage("플러그인이 이동을 취소했습니다."); return
          }
          // Keep ACTIVE ownership and include the player in the post-teleport checkpoint.
          completions.add(() => offThread("local route acknowledgement", () => {
            try {
              val arrived: Destination = routes.completeLocal(route, owner)
              admitted.computeIfPresent(id, (key, current) => current.copy(destination = arrived))
              ()
            } finally { localTravel.remove(id) }
          }, null))
          return
        }
        // No storage wait on the tick: the next checkpoint captures and freezes this player, and the
        // hand-off continues once that checkpoint has committed (see handOff).
        remoteRequests.put(id, PendingRemote(route, owner)); nextSaveNanos = 0
      } catch {
        case e: Exception =>
          if (NativeJournalImpl.fatal(e)) throw new FatalJournalException(e)
          // Never leave a claimed route behind: cancel it so the player can simply try again.
          MinecraftServer.LOGGER.warn("Link travel request {} failed; cancelling it", route.id, e)
          localTravel.remove(id)
          offThread("route rejection", () => routes.reject(route), null)
          player.getBukkitEntity().sendMessage("일시적인 오류로 이동을 취소했습니다. 다시 시도하세요.")
      }
    }
  }
}
