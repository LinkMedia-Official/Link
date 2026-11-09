package io.linkmc.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.{Continuation, EventTask, PostOrder, Subscribe}
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.{PlayerChooseInitialServerEvent, ServerPostConnectEvent, ServerPreConnectEvent}
import com.velocitypowered.api.event.proxy.{ProxyInitializeEvent, ProxyShutdownEvent}
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.{Player, ProxyServer}
import io.linkmc.api.LinkApi.Reservation
import io.linkmc.core.*
import net.kyori.adventure.text.Component
import org.slf4j.Logger

import java.nio.file.Path
import java.util.{List as JList, Map as JMap, Objects, Set as JSet, UUID}
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend names in velocity.toml must equal LINK_NODE_ID; modern forwarding is required.
 * Plugin metadata is in src/main/resources/velocity-plugin.json (Velocity's annotation processor only runs on javac).
 */
@Plugin(id = "link", name = "Link", version = "0.1.0-SNAPSHOT", description = "Link transactional backend routing")
final class LinkVelocity @Inject() (proxy: ProxyServer, logger: Logger, @DataDirectory dataDirectory: Path) {
  import LinkVelocity.Pending

  private val worker: ExecutorService = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](256), Thread.ofPlatform().name("Link-Proxy-", 0).factory(), new ThreadPoolExecutor.AbortPolicy())
  private val waits: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("Link-Proxy-Wait").factory())
  private val pending: JMap[UUID, Pending] = new ConcurrentHashMap[UUID, Pending]()
  private val travelling: JSet[UUID] = ConcurrentHashMap.newKeySet[UUID]()
  private var network: NetworkStore = null
  private var arrivals: ArrivalStore = null
  private var routes: RouteStore = null
  private var database: Database = null
  private var service: LinkService = null
  def api(): io.linkmc.api.LinkApi = Objects.requireNonNull(service, "Link proxy not initialized")
  private val polling = new AtomicBoolean()

  @Subscribe
  @throws[Exception]
  def initialize(event: ProxyInitializeEvent): Unit = {
    val config = VelocityConfiguration.load(dataDirectory, System.getenv())
    val db = Database.pooled(config.jdbcUrl(), config.username(), config.password()); database = db; db.migrate()
    service = new LinkService(db); RuntimeProvider.install(service)
    network = new NetworkStore(db); arrivals = new ArrivalStore(db); routes = new RouteStore(db)
    proxy.getScheduler.buildTask(this, (() => {
      if (polling.compareAndSet(false, true)) {
        try worker.execute(() => { try dispatchReady() catch { case e: Exception => logger.error("Link route dispatch failed", e) } finally polling.set(false) })
        catch { case _: RejectedExecutionException => polling.set(false) }
      }
    }): Runnable).repeat(java.time.Duration.ofMillis(500)).schedule()
  }

  @Subscribe
  def initial(event: PlayerChooseInitialServerEvent): EventTask =
    EventTask.withContinuation(continuation => {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
      try worker.execute(() => allocate(event, continuation, deadline))
      catch {
        case _: RejectedExecutionException =>
          event.getPlayer.disconnect(Component.text("접속 요청이 많습니다. 잠시 후 다시 시도해 주세요.")); continuation.resume()
      }
    })

  /**
   * A reconnect must wait for the old backend's final save/release, never steal its inventory. The wait is a
   * re-scheduled check, not a sleeping worker, so slow saves for a few players cannot stall every login/transfer.
   */
  private def allocate(event: PlayerChooseInitialServerEvent, continuation: Continuation, deadline: Long): Unit = {
    val player = event.getPlayer
    var resume = true
    try {
      if (!new SessionStore(database, 30).available(player.getUniqueId)) {
        if (!player.isActive || System.nanoTime() >= deadline) throw new ConflictException("Previous player session is still saving")
        waits.schedule((() => {
          try worker.execute(() => allocate(event, continuation, deadline))
          catch {
            case _: RejectedExecutionException =>
              player.disconnect(Component.text("접속 요청이 많습니다. 잠시 후 다시 시도해 주세요.")); continuation.resume()
          }
        }): Runnable, 100, TimeUnit.MILLISECONDS)
        resume = false
      } else {
        if (!player.isActive) throw new ConflictException("Connection closed during allocation")
        val ticket = UUID.randomUUID(); val reservation = network.reserve(player.getUniqueId, ticket)
        val request = Pending(ticket, reservation, player); pending.put(player.getUniqueId, request)
        val target = proxy.getServer(reservation.destination().node()).orElseThrow(() => new IllegalStateException("Unregistered Link backend"))
        arrivals.issue(ticket, reservation, null); event.setInitialServer(target)
      }
    } catch {
      case _: RejectedExecutionException =>
        player.disconnect(Component.text("접속 요청이 많습니다. 잠시 후 다시 시도해 주세요.")); cleanup(player.getUniqueId, player)
      case e: Exception =>
        logger.error("Link initial allocation failed", e); player.disconnect(Component.text("스폰을 준비하지 못했습니다. 잠시 후 다시 접속해 주세요.")); cleanup(player.getUniqueId, player)
    } finally if (resume) continuation.resume()
  }

  @Subscribe(order = PostOrder.LAST)
  def preConnect(event: ServerPreConnectEvent): Unit = {
    val request = pending.get(event.getPlayer.getUniqueId)
    if (request == null || (request.connection ne event.getPlayer) || !request.reservation.destination().node().equals(event.getResult.getServer.map(s => s.getServerInfo.getName).orElse(""))) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied())
    }
  }

  @Subscribe
  def connected(event: ServerPostConnectEvent): Unit = {
    pending.computeIfPresent(event.getPlayer.getUniqueId, (_, p) => if (p.connection eq event.getPlayer) null else p)
    travelling.remove(event.getPlayer.getUniqueId)
  }

  @Subscribe
  def disconnected(event: DisconnectEvent): Unit = {
    travelling.remove(event.getPlayer.getUniqueId)
    try worker.execute(() => cleanup(event.getPlayer.getUniqueId, event.getPlayer))
    catch { case _: RejectedExecutionException => () }
  }

  @throws[Exception]
  private def dispatchReady(): Unit = {
    val players = proxy.getAllPlayers.stream().map[UUID](p => p.getUniqueId).toList
    val ready = routes.readyFor(players).iterator()
    while (ready.hasNext) {
      val route = ready.next()
      val player = proxy.getPlayer(route.reservation.player()).orElse(null)
      if (player != null && player.isActive && routes.dispatch(route.id)) {
        val id = player.getUniqueId; pending.put(id, Pending(route.id, route.reservation, player)); travelling.add(id)
        try {
          val target = proxy.getServer(route.reservation.destination().node()).orElseThrow(() => new IllegalStateException("Unregistered Link backend"))
          if (player.getCurrentServer.map[java.lang.Boolean](s => s.getServerInfo.getName.equals(route.reservation.destination().node())).orElse(false)) {
            routes.finish(route.id, true); pending.remove(id); travelling.remove(id)
          } else {
            player.createConnectionRequest(target).connect().whenComplete((result, error) => {
              val success = error == null && result.isSuccessful
              try worker.execute(() => { try routes.finish(route.id, success) catch { case e: Exception => logger.error("Route completion recording failed", e) } })
              catch { case _: RejectedExecutionException => () }
              if (!success) player.disconnect(Component.text("서버 이동에 실패했습니다. 다시 접속해 주세요."))
            })
          }
        } catch {
          case e: Exception =>
            routes.finish(route.id, false); cleanup(id, player); travelling.remove(id); player.disconnect(Component.text("서버 이동에 실패했습니다. 다시 접속해 주세요.")); logger.error("Link route failed", e)
        }
      }
    }
    val entries = JList.copyOf(pending.entrySet()).iterator()
    while (entries.hasNext) {
      val entry = entries.next()
      if (entry.getValue.reservation.expiresAt().isBefore(java.time.Instant.now())) {
        entry.getValue.connection.disconnect(Component.text("이동 예약이 만료되었습니다. 다시 접속해 주세요.")); cleanup(entry.getKey, entry.getValue.connection); travelling.remove(entry.getKey)
      }
    }
  }

  private def cleanup(id: UUID, connection: Player): Unit = {
    val request = pending.get(id)
    if (request == null || (request.connection ne connection) || !pending.remove(id, request)) return
    try { routes.finish(request.ticket, false); arrivals.cancel(request.ticket, id); network.cancel(request.reservation.id(), id) }
    catch { case e: Exception => logger.warn("Arrival cleanup deferred until expiry", e) }
  }

  @Subscribe
  def shutdown(event: ProxyShutdownEvent): Unit = {
    waits.shutdownNow(); worker.shutdownNow()
    if (service != null) { RuntimeProvider.uninstall(service); service.close() }
    if (database != null) database.close()
  }
}

object LinkVelocity {
  private final case class Pending(ticket: UUID, reservation: Reservation, connection: Player)
}
