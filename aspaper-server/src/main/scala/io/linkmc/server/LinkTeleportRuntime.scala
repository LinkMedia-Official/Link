package io.linkmc.server

import io.linkmc.api.LinkApi
import io.linkmc.core.{Database, LinkService, TeleportStore}
import java.util.{HashMap, HashSet, List, Locale, Map, Set, UUID}
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit}
import org.bukkit.Bukkit
import org.bukkit.persistence.PersistentDataType

/** Resolves consented destinations on the destination player's own server thread. */
final class LinkTeleportRuntime private[server] (db: Database, service: LinkService, node: String, capacity: Int) extends AutoCloseable {
  private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Teleport-Requests").factory())
  private val busy: Set[UUID] = ConcurrentHashMap.newKeySet()
  private val seen: Map[UUID, String] = new HashMap[UUID, String]()
  @volatile private var online: List[String] = List.of()
  @volatile private var notices: List[LinkApi.TeleportRequest] = List.of()
  @volatile private var closed: Boolean = false
  private val store: TeleportStore = new TeleportStore(db)

  private[server] def start(): Unit = { worker.scheduleWithFixedDelay(() => poll(), 0, 1, TimeUnit.SECONDS); () }
  private[server] def suggest(player: UUID, action: String, prefix: String): List[String] = {
    val candidates: List[String] = if (action.equals("tpaccept") || action.equals("tpdeny"))
      notices.stream().filter((r: LinkApi.TeleportRequest) => r.recipient().equals(player) && r.state().equals("PENDING")).map[String]((r: LinkApi.TeleportRequest) => r.requesterName()).toList()
    else online
    candidates.stream().filter((n: String) => n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).distinct().sorted().toList()
  }
  private def poll(): Unit = {
    if (!(closed || LinkRuntimeImpl.isClosing())) {
      try {
        online = store.onlinePlayers(); notices = store.notices(node); val snapshot = notices
        if (!(closed || LinkRuntimeImpl.isClosing())) {
          net.minecraft.server.MinecraftServer.getServer().execute(() => notifyPlayers(snapshot))
          val tasks = store.work(node).iterator()
          while (tasks.hasNext) {
            val task = tasks.next()
            if (busy.add(task.request.id())) net.minecraft.server.MinecraftServer.getServer().execute(() => resolve(task))
          }
        }
      } catch { case error: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Teleport request polling deferred", error) }
    }
  }
  private def resolve(task: TeleportStore.Work): Unit = {
    val id = task.request.id()
    if (closed) busy.remove(id)
    else if (task.destination != null) submit(task, task.destination)
    else {
      val host = if (task.request.here()) task.request.requester() else task.request.recipient()
      val player = Bukkit.getPlayer(host)
      if (player == null || !player.isOnline() || NativeJournalImpl.moving(host) || player.getPersistentDataContainer().has(LinkEditors.KEY, PersistentDataType.STRING)) {
        service.rejectTeleport(id).whenComplete((v: Void, e: Throwable) => { busy.remove(id); () })
      } else {
        val location = player.getLocation()
        val destination = if (task.destination != null) task.destination else new LinkApi.Destination(node, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch())
        submit(task, destination)
      }
    }
  }
  private def submit(task: TeleportStore.Work, destination: LinkApi.Destination): Unit = {
    service.submitTeleport(task, destination, capacity).whenComplete((v: Void, error: Throwable) => {
      busy.remove(task.request.id())
      if (error != null) net.minecraft.server.MinecraftServer.LOGGER.debug("Teleport request was deferred or rejected", error)
    })
  }
  private def notifyPlayers(snapshot: List[LinkApi.TeleportRequest]): Unit = {
    if (!(closed || LinkRuntimeImpl.isClosing())) {
      val current: Set[UUID] = new HashSet[UUID]()
      val requests = snapshot.iterator()
      while (requests.hasNext) {
        val request = requests.next()
        current.add(request.id())
        val from = Bukkit.getPlayer(request.requester()); val to = Bukkit.getPlayer(request.recipient())
        if (if (request.state().equals("PENDING")) to == null else from == null) ()
        else if (request.state().equals(seen.put(request.id(), request.state()))) ()
        else if (request.state().equals("PENDING") && to != null) {
          to.sendMessage(request.requesterName() + (if (request.here()) "님이 자신의 위치로 와 달라는 티피 요청을 보냈습니다." else "님이 당신에게 이동하는 티피 요청을 보냈습니다."))
          to.sendMessage("/티피수락 " + request.requesterName() + " 또는 /티피거절 " + request.requesterName() + " (요청은 60초 후 만료)")
        } else if (from != null && Set.of("ACCEPTED", "DISPATCHED", "DECLINED", "CANCELLED", "EXPIRED", "FAILED").contains(request.state())) {
          from.sendMessage("티피 요청: " + request.recipientName() + " — " + (request.state() match {
            case "ACCEPTED" | "DISPATCHED" => "상대가 수락했습니다. 이동 요청을 처리합니다."
            case "DECLINED" => "상대가 거절했습니다."
            case "CANCELLED" => "취소되었습니다."
            case "EXPIRED" => "만료되었습니다."
            case _ => "이동 준비에 실패했습니다. 접속 상태와 정원을 확인하세요."
          }))
        }
      }
      seen.keySet().retainAll(current)
    }
  }
  override def close(): Unit = {
    closed = true; worker.shutdownNow()
    try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("Teleport scheduler did not stop") }
    catch { case e: InterruptedException => Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while stopping teleport scheduler", e) }
  }
}
