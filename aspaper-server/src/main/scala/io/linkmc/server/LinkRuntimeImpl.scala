package io.linkmc.server

import io.linkmc.api.LinkApi
import io.linkmc.api.LinkApi.{Data, Destination, Engine, Network, Session, Sessions, Teleports, Utilities}
import io.linkmc.core.{ConflictException, Database, EventRetention, LinkService, NetworkStore, RedisOutbox, RouteStore, RuntimeProvider, SessionStore}
import java.util.concurrent.{CompletableFuture, CompletionStage, Executors, ScheduledExecutorService, TimeUnit}

/** Native bootstrap; tooling mode is explicitly NOT a durable gameplay implementation. */
final class LinkRuntimeImpl private (db: Database, node0: String) extends LinkApi {
  private val notifications: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Notifications").factory())
  private val maintenance: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Retention").factory())
  private val heartbeat: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Heartbeat").factory())
  private var redis: RedisOutbox = null
  private var teleportRuntime: LinkTeleportRuntime = null
  @volatile private var closing: Boolean = false

  private val database: Database = db
  private val node: String = node0
  private val networkStore: NetworkStore = new NetworkStore(db)
  private val leases: SessionStore = new SessionStore(db, 30)
  @volatile private var nodeLease: Session = if (NativeJournalImpl.enabled()) NativeJournalImpl.startupOwner() else leases.acquireNode(node)
  db.nodeOwner(() => nodeLease)
  private val service: LinkService = new LinkService(db, (route: RouteStore.Request) => NativeJournalImpl.submitRoute(route))

  private def fenceWorkers(): Unit = { closing = true; if (teleportRuntime != null) teleportRuntime.close() }
  private def deliverNotifications(uri: String): Unit = this.synchronized {
    if (!closing) {
      try {
        if (redis == null) redis = new RedisOutbox(database, uri)
        redis.flush(64)
      } catch { case e: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Redis delivery deferred; SQL outbox retained", e) }
    }
  }
  private def closeNotifications(): Unit = this.synchronized { if (redis != null) { redis.close(); redis = null } }
  private def pruneEvents(config: LinkConfiguration): Unit = {
    var events = 0; var operations = 0; var notifications = 0; var requests = 0
    try {
      val retention = new EventRetention(database)
      var i = 0; var done = false
      while (!done && i < config.retentionBatches() && !closing && !Thread.currentThread().isInterrupted()) {
        val result = retention.prune(config.retentionHours(), config.retentionBatch())
        events += result.events; operations += result.operations; notifications += result.notifications; requests += result.requests
        if (result.operations < config.retentionBatch() && result.notifications < config.retentionBatch() && result.requests < config.retentionBatch()) done = true
        else i += 1
      }
      if (events > 0 || notifications > 0 || requests > 0) net.minecraft.server.MinecraftServer.LOGGER.info("Link retention: pruned {} events from {} historical operations, {} delivered notifications and {} expired requests", Integer.valueOf(events), Integer.valueOf(operations), Integer.valueOf(notifications), Integer.valueOf(requests))
    } catch { case error: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Link retention deferred; current state preserved", error) }
  }
  private def close(): Unit = {
    closing = true
    var saved = false
    try {
      if (teleportRuntime != null) teleportRuntime.close()
      notifications.shutdownNow(); maintenance.shutdownNow()
      if (NativeJournalImpl.enabled()) NativeJournalImpl.shutdown()
      saved = true
    } catch {
      case e: Exception =>
        NativeJournalImpl.fenceOwnership()
        if (NativeJournalImpl.enabled()) try { NativeJournalImpl.shutdown() } catch { case cleanup: Exception => e.addSuppressed(cleanup) }
        net.minecraft.server.MinecraftServer.LOGGER.error("Link final checkpoint failed; ownership will not be explicitly released", e)
    } finally {
      heartbeat.shutdownNow()
      notifications.shutdownNow(); maintenance.shutdownNow()
      try {
        if (!heartbeat.awaitTermination(15, TimeUnit.SECONDS)) saved = false
        service.close()
        if (saved) leases.releaseNode(nodeLease)
      } catch { case e: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Link ownership cleanup deferred or already fenced", e) }
      finally { closeNotifications(); database.close() }
    }
  }
  override def data(): Data = service.data()
  override def sessions(): Sessions = service.sessions()
  override def network(): Network = service.network()
  override def teleports(): Teleports = service.teleports()
  override def utilities(): Utilities = new LinkUtilities()
  override def engine(): Engine = new Engine {
    override def journalEnabled(): Boolean = NativeJournalImpl.enabled()
    override def transact(mutation: Runnable): CompletionStage[Void] = {
      java.util.Objects.requireNonNull(mutation)
      val result = new CompletableFuture[Void]()
      if (!NativeJournalImpl.enabled()) CompletableFuture.failedFuture[Void](new IllegalStateException("Native journal disabled"))
      else if (!NativeJournalImpl.watch(result)) result
      else {
        net.minecraft.server.MinecraftServer.getServer().execute(() => {
          try { NativeJournalImpl.run(() => { mutation.run(); NativeJournalImpl.complete[Void](result, null) }) }
          catch { case error: Throwable => result.completeExceptionally(error); throw error }
        })
        result
      }
    }
  }
}

object LinkRuntimeImpl {
  @volatile private var instance: LinkRuntimeImpl = null
  /** Lease is 30s; stop 5s before it could expire so no transaction ever runs on a lapsed lease. */
  private final val LEASE_SAFE_SECONDS = 25L

  /** Called from PaperWorldLoader before any default dimension is constructed. */
  def beforeWorlds(): Unit = {
    val config = LinkConfiguration.get()
    val mode = config.mode()
    if (mode.equals("disabled")) return
    if (!mode.equals("tools") && !mode.equals("journal")) throw new IllegalStateException("Link strict gameplay mode is not yet validated. Use journal only in an isolated integration environment.")
    if (instance != null) throw new IllegalStateException("Link initialized twice")
    NativeJournalImpl.watchGameThreadSql()
    if (mode.equals("journal")) {
      val forwarding = io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity
      if (!forwarding.enabled || !forwarding.onlineMode || forwarding.secret.isBlank()) throw new IllegalStateException("Link journal requires authenticated Velocity modern forwarding")
    }
    try {
      val url = config.jdbcUrl(); val user = config.username(); val password = config.password()
      val node = config.node()
      val db = if (NativeJournalImpl.enabled()) NativeJournalImpl.database() else Database.pooled(url, user, password); db.migrate()
      val runtime = new LinkRuntimeImpl(db, node)
      try {
        // Worlds are plain vanilla worlds: Paper loads level-name (and its nether/end) as usual.
        // Exported worlds an older build kept in the database must exist on disk before Paper reads world data.
        LinkWorldExport.beforeWorlds()
        instance = runtime; RuntimeProvider.install(runtime)
        val lastRenewed = Array[Long](System.nanoTime())
        runtime.heartbeat.scheduleWithFixedDelay(() => {
          try {
            val attempt = System.nanoTime() // the DB sets expiry after this instant: a conservative bound
            val renewed =
              try { runtime.nodeLease = runtime.leases.renew(runtime.nodeLease); lastRenewed(0) = attempt; true }
              catch {
                case leaseError: Exception =>
                  // A database outage is not ownership loss while our 30s lease is still valid: keep playing
                  // and retry. Stop only on a definite conflict or once the lease may have expired.
                  if (leaseError.isInstanceOf[ConflictException] || System.nanoTime() - lastRenewed(0) > TimeUnit.SECONDS.toNanos(LEASE_SAFE_SECONDS)) throw leaseError
                  net.minecraft.server.MinecraftServer.LOGGER.warn("Link node lease renewal failed; retrying (lease still valid for ~{}s)", java.lang.Long.valueOf(LEASE_SAFE_SECONDS - TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastRenewed(0))), leaseError)
                  false
              }
            if (renewed) {
              // Player-session renewal and route polling are retried every 5s. Only an ownership conflict
              // is fatal; a transient SQL error must not shut the backend down.
              try { NativeJournalImpl.renew() }
              catch { case sessionError: Exception => if (sessionError.isInstanceOf[ConflictException] || NativeJournalImpl.fatal(sessionError)) throw sessionError; net.minecraft.server.MinecraftServer.LOGGER.warn("Link player lease renewal deferred; retrying in 5s", sessionError) }
              if (!runtime.closing) {
                if (NativeJournalImpl.enabled()) {
                  try { runtime.networkStore.heartbeat(node, config.nodeCapacity()) } catch { case networkError: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Link network heartbeat deferred; node lease renewed", networkError) }
                }
              }
            }
          } catch {
            case e: Exception =>
              net.minecraft.server.MinecraftServer.LOGGER.error("Link could not maintain ownership after bounded retries; stopping this backend", e)
              NativeJournalImpl.fenceOwnership(); try { runtime.fenceWorkers() } finally { org.bukkit.Bukkit.shutdown() }
          }
        }, 5, 5, TimeUnit.SECONDS)
        NativeJournalImpl.finishStartup()
      } catch { case e: Exception => runtime.close(); throw e }
    } catch { case e: Exception => throw new IllegalStateException("Link bootstrap failed", e) }
  }
  def afterWorlds(): Unit = {
    if (instance == null) return
    val runtime = instance
    val config = LinkConfiguration.get()
    try {
      if (NativeJournalImpl.enabled()) {
        LinkWorldExport.afterWorlds()
        LinkWorldExport.autoload(config.autoloadWorlds())
        NativeJournalImpl.activate()
        runtime.networkStore.heartbeat(runtime.node, config.nodeCapacity())
        runtime.networkStore.reconcileNode(runtime.nodeLease)
        // First joins are allocated to a node's main world; players then spawn where vanilla places them.
        val main = org.bukkit.Bukkit.getWorlds().getFirst(); val spawn = main.getSpawnLocation()
        runtime.networkStore.register(runtime.node + ":" + main.getName(), new Destination(runtime.node, main.getName(), spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch()), config.nodeCapacity())
        runtime.networkStore.syncWarpWorlds(runtime.node, org.bukkit.Bukkit.getWorlds().stream().map[String](w => w.getName()).toList())
        LinkWorldEvents.register(runtime.networkStore, runtime.node)
      }
      val redisUri = config.redisUri()
      if (redisUri != null && !redisUri.isBlank()) runtime.notifications.scheduleWithFixedDelay(() => runtime.deliverNotifications(redisUri), 0, 5, TimeUnit.SECONDS)
      LinkUtilityCommand.register(runtime.utilities())
      if (NativeJournalImpl.enabled()) {
        runtime.teleportRuntime = new LinkTeleportRuntime(runtime.database, runtime.service, runtime.node, config.worldCapacity())
        LinkTeleportCommand.register(runtime.teleports(), runtime.teleportRuntime); runtime.teleportRuntime.start()
      }
      if (config.retentionEnabled()) {
        net.minecraft.server.MinecraftServer.LOGGER.info("Link event retention enabled: {} hours; latest state/transaction anchors retained", Integer.valueOf(config.retentionHours()))
        runtime.maintenance.scheduleWithFixedDelay(() => runtime.pruneEvents(config), config.retentionInterval(), config.retentionInterval(), TimeUnit.SECONDS)
      }
    } catch { case e: Exception => throw new IllegalStateException("Link activation failed", e) }
  }
  def stop(): Unit = synchronized { val runtime = instance; if (runtime != null) { runtime.closing = true; RuntimeProvider.uninstall(runtime); try { runtime.close() } finally { instance = null } } }
  private[server] def isClosing(): Boolean = { val runtime = instance; val server = net.minecraft.server.MinecraftServer.getServer(); runtime == null || runtime.closing || (server != null && server.hasStopped()) }
  def beginShutdown(): Unit = { val runtime = instance; if (runtime != null) try { runtime.fenceWorkers() } catch { case error: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Link shutdown worker drain failed", error) } }
  private[server] def fence(): Unit = { val runtime = instance; if (runtime != null) { runtime.heartbeat.shutdownNow(); () } }
}
