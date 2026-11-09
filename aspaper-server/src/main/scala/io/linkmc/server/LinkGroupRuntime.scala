package io.linkmc.server

import io.linkmc.api.LinkApi
import io.linkmc.core.{Database, RouteStore, WarpGroupStore}
import java.util.Set
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit}
import java.util.function.Supplier

final class LinkGroupRuntime private[server] (db: Database, worlds: LinkApi.Worlds, node: String, owner: Supplier[LinkApi.Session]) extends AutoCloseable {
  private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Warp-Groups").factory())
  private val busy: Set[String] = ConcurrentHashMap.newKeySet()
  @volatile private var closed: Boolean = false
  private val store: WarpGroupStore = new WarpGroupStore(db)

  private[server] def start(): Unit = { worker.scheduleWithFixedDelay(() => poll(), 0, 1, TimeUnit.SECONDS); () }
  private def poll(): Unit = {
    if (!(closed || LinkRuntimeImpl.isClosing())) {
      try {
        val pendings = store.pending(node).iterator()
        while (pendings.hasNext) {
          val pending = pendings.next()
          val route = store.dispatch(pending); route.ifPresent((r: RouteStore.Request) => NativeJournalImpl.submitRoute(r))
          if (route.isEmpty() && store.status(pending.id).equals("FAILED")) net.minecraft.server.MinecraftServer.getServer().execute(() => { val player = org.bukkit.Bukkit.getPlayer(pending.player); if (player != null) player.sendMessage("워프 채널 배정이 만료되었거나 접속 상태가 바뀌었습니다. 정원·서버 상태를 확인하고 다시 시도하세요.") })
        }
        store.provisionDemand()
        val jobs = store.jobs(node).iterator()
        while (jobs.hasNext) {
          val job = jobs.next()
          if (busy.add(job.id)) worlds.ensurePersistent(job.world, job.template, job.version)
            .thenCompose[LinkApi.Destination]((w: LinkApi.WorldInfo) => worlds.spawn(w.name())).whenComplete((spawn: LinkApi.Destination, error: Throwable) => {
              try { if (!closed) { if (error != null) net.minecraft.server.MinecraftServer.LOGGER.warn("Warp channel creation deferred: {}", job.id, error) else store.ready(job, spawn, owner.get()) } }
              catch { case e: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Warp channel activation deferred", e) }
              finally { busy.remove(job.id) }
            })
        }
      } catch { case e: Exception => net.minecraft.server.MinecraftServer.LOGGER.warn("Warp group polling deferred", e) }
    }
  }
  override def close(): Unit = {
    closed = true; worker.shutdownNow()
    try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("Group scheduler did not stop") }
    catch { case e: InterruptedException => Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while stopping group scheduler", e) }
  }
}
