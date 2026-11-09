package io.linkmc.core

import io.linkmc.api.LinkApi
import io.linkmc.api.LinkApi.{Change, Channel, Commit, Destination, Event, Reservation, Session, State, TeleportRequest, Transaction, Transfer, WarpGroup}

import java.util.{List, Objects, Optional, UUID}
import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, CompletionException, CompletionStage, ExecutorService, RejectedExecutionException, ThreadPoolExecutor, TimeUnit}

/** Bounded async facade. The caller owns DB pool and server-thread dispatch. */
final class LinkService(database: Database, routeSubmitted: java.util.function.Consumer[RouteStore.Request]) extends LinkApi with AutoCloseable {
  Objects.requireNonNull(routeSubmitted)
  private val events = new EventStore(database); private val sessionStore = new SessionStore(database, 30); private val networkStore = new NetworkStore(database)
  private val routes = new RouteStore(database)
  private val teleportStore = new TeleportStore(database); private val groups = new WarpGroupStore(database)
  // FIFO submission order within one runtime. Across runtimes, versions and
  // fencing reject stale work; callers must explicitly reconcile conflicts.
  private val executor: ExecutorService = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](1024),
    Thread.ofPlatform().name("Link-Database-", 0).factory(), new ThreadPoolExecutor.AbortPolicy())

  def this(database: Database) =
    this(database, (request: RouteStore.Request) => ())

  private def async[T](task: LinkService.Task[T]): CompletionStage[T] =
    try {
      CompletableFuture.supplyAsync[T](() => {
        try task.run() catch { case e: Exception => throw new CompletionException(e) }
      }, executor)
    } catch { case e: RejectedExecutionException => CompletableFuture.failedFuture[T](e) }

  override def data(): LinkApi.Data = new LinkApi.Data {
    override def read(id: String): CompletionStage[Optional[State]] = async[Optional[State]](() => events.read(id))
    override def commit(tx: Transaction): CompletionStage[Commit] = async[Commit](() => {
      if (tx.changes().stream().anyMatch((c: Change) => c.id().startsWith("native:") || c.id().startsWith("player:") || c.id().startsWith("warp:") || c.id().startsWith("tpa:") || c.id().startsWith("group-request:") || c.id().startsWith("group-world:")))
        throw new IllegalArgumentException("Reserved state: use engine().transact for native state or the network warp API")
      events.commit(tx)
    })
    override def history(id: String, after: Long, limit: Int): CompletionStage[List[Event]] = async[List[Event]](() => events.history(id, after, limit))
  }
  override def sessions(): LinkApi.Sessions = new LinkApi.Sessions {
    override def acquire(player: UUID, node: String): CompletionStage[Session] = async[Session](() => sessionStore.acquire(player, node))
    override def renew(session: Session): CompletionStage[Session] = async[Session](() => sessionStore.renew(session))
    override def release(session: Session): CompletionStage[Void] = async[Void](() => { sessionStore.release(session); null })
    override def prepare(s: Session, op: UUID, d: Destination): CompletionStage[Transfer] = async[Transfer](() => sessionStore.prepare(s, op, d))
    override def accept(p: UUID, t: UUID, node: String): CompletionStage[Session] = async[Session](() => sessionStore.accept(p, t, node))
  }
  override def teleports(): LinkApi.Teleports = new LinkApi.Teleports {
    override def request(player: UUID, recipient: String, here: Boolean): CompletionStage[TeleportRequest] = async[TeleportRequest](() => teleportStore.request(player, recipient, here))
    override def respond(player: UUID, requester: String, accept: Boolean): CompletionStage[TeleportRequest] = async[TeleportRequest](() => teleportStore.respond(player, requester, accept))
    override def cancel(player: UUID): CompletionStage[java.lang.Boolean] = async[java.lang.Boolean](() => Boolean.box(teleportStore.cancel(player)))
    override def incoming(player: UUID): CompletionStage[List[TeleportRequest]] = async[List[TeleportRequest]](() => teleportStore.incoming(player))
    override def onlinePlayers(): CompletionStage[List[String]] = async[List[String]](() => teleportStore.onlinePlayers())
  }
  def rejectTeleport(id: UUID): CompletionStage[Void] = async[Void](() => { teleportStore.finish(id, false); null })
  def submitTeleport(task: TeleportStore.Work, destination: Destination, capacity: Int): CompletionStage[Void] = async[Void](() => {
    val id = task.request.id()
    if (!routes.status(id).equals("MISSING")) { teleportStore.finish(id, true); null }
    else {
      val mover = if (task.request.here()) task.request.recipient() else task.request.requester()
      try {
        val fixed = teleportStore.resolve(id, destination)
        teleportStore.dispatchable(id); networkStore.prepareDestination(fixed, capacity)
        val reservation = networkStore.reserve(mover, id, fixed); routes.request(id, reservation); teleportStore.finish(id, true)
        try routeSubmitted.accept(new RouteStore.Request(id, reservation)) catch { case ignored: RuntimeException => }
      } catch {
        case error @ (_: TeleportStore.Rejected | _: ConflictException) =>
          if (!routes.status(id).equals("MISSING")) teleportStore.finish(id, true)
          else { networkStore.cancel(id, mover); teleportStore.finish(id, false) }
          throw error
      }
      null
    }
  })
  override def warpGroups(): LinkApi.WarpGroups = new LinkApi.WarpGroups {
    override def bind(name: String, template: String, version: Int, node: String): CompletionStage[Void] = async[Void](() => { groups.bind(name, template, version, node); null })
    override def list(): CompletionStage[List[WarpGroup]] = async[List[WarpGroup]](() => groups.list())
    override def channels(name: String): CompletionStage[List[Channel]] = async[List[Channel]](() => groups.channels(name))
    override def status(id: UUID): CompletionStage[String] = async[String](() => { val routed = routes.status(id); if (routed.equals("MISSING")) groups.status(id) else routed })
    override def travel(player: UUID, operation: UUID, name: String): CompletionStage[Void] =
      async[java.lang.Boolean](() => if (groups.exists(name)) { groups.enqueue(player, operation, name); java.lang.Boolean.TRUE } else java.lang.Boolean.FALSE).thenCompose[Void]((group: java.lang.Boolean) => {
        if (group.booleanValue()) CompletableFuture.completedFuture[Void](null)
        else LinkService.this.network().warp(name).thenCompose[Void]((target: Optional[Destination]) => {
          if (target.isEmpty()) CompletableFuture.failedFuture[Void](new ConflictException("Unknown warp"))
          else LinkService.this.network().requestTravel(player, operation, target.get()).thenApply[Void]((r: Reservation) => null)
        })
      })
  }
  override def network(): LinkApi.Network = new LinkApi.Network {
    override def channels(): CompletionStage[List[Channel]] = async[List[Channel]](() => networkStore.channels())
    override def registerChannel(id: String, spawn: Destination, capacity: Int): CompletionStage[Void] = async[Void](() => { networkStore.register(id, spawn, capacity); null })
    override def setChannelReady(id: String, ready: Boolean): CompletionStage[Void] = async[Void](() => { networkStore.ready(id, ready); null })
    override def reserve(player: UUID, request: UUID): CompletionStage[Reservation] = async[Reservation](() => networkStore.reserve(player, request))
    override def reserve(player: UUID, request: UUID, destination: Destination): CompletionStage[Reservation] = async[Reservation](() => networkStore.reserve(player, request, destination))
    override def cancel(r: UUID, p: UUID): CompletionStage[Void] = async[Void](() => { networkStore.cancel(r, p); null })
    override def warp(name: String): CompletionStage[Optional[Destination]] = async[Optional[Destination]](() => networkStore.warp(name))
    override def setWarp(name: String, d: Destination): CompletionStage[Void] = async[Void](() => { networkStore.setWarp(name, d); null })
    override def setWarp(name: String, d: Destination, capacity: Int): CompletionStage[Void] = async[Void](() => { networkStore.setWarp(name, d, capacity); null })
    override def deleteWarp(name: String): CompletionStage[java.lang.Boolean] = async[java.lang.Boolean](() => Boolean.box(networkStore.deleteWarp(name)))
    override def warps(): CompletionStage[List[String]] = async[List[String]](() => networkStore.warps())
    override def requestTravel(player: UUID, operation: UUID, destination: Destination): CompletionStage[Reservation] = async[Reservation](() => {
      val reservation = routes.requestTravel(player, operation, destination)
      // This is a wake-up hint after durable submission. Polling remains the recovery path.
      try routeSubmitted.accept(new RouteStore.Request(operation, reservation))
      catch { case ignored: RuntimeException => }
      reservation
    })
    override def travelStatus(operation: UUID): CompletionStage[String] = async[String](() => routes.status(operation))
  }
  override def close(): Unit = {
    executor.shutdown()
    try { if (!executor.awaitTermination(15, TimeUnit.SECONDS)) executor.shutdownNow() }
    catch { case e: InterruptedException => executor.shutdownNow(); Thread.currentThread().interrupt() }
  }
}

object LinkService {
  @FunctionalInterface private trait Task[T] {
    @throws[Exception]
    def run(): T
  }
}
