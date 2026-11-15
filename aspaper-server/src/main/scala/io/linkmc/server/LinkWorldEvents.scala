package io.linkmc.server

import io.linkmc.core.NetworkStore
import net.minecraft.server.MinecraftServer
import org.bukkit.Bukkit
import org.bukkit.event.{EventHandler, EventPriority, Listener}
import org.bukkit.event.world.{WorldLoadEvent, WorldUnloadEvent}

import java.util.concurrent.{ExecutorService, Executors, RejectedExecutionException}

/** Keeps travel destination channels in step with loaded vanilla worlds (any plugin may load/unload them). */
private[server] final class LinkWorldEvents private (network: NetworkStore, node: String) extends Listener {
  private val worker: ExecutorService = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Link-World-Channels").factory())

  private def async(what: String, action: () => Unit): Unit =
    try worker.execute(() => { try action() catch { case error: Exception => MinecraftServer.LOGGER.warn("Link {} deferred", what, error) } })
    catch { case closed: RejectedExecutionException => () }

  @EventHandler(priority = EventPriority.MONITOR)
  def loaded(event: WorldLoadEvent): Unit = { val name = event.getWorld().getName(); async("world channel open", () => network.openWarpWorld(node, name)) }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  def unloaded(event: WorldUnloadEvent): Unit = { val name = event.getWorld().getName(); async("world channel close", () => network.closeWorld(node, name)) }
}

private[server] object LinkWorldEvents {
  def register(network: NetworkStore, node: String): Unit =
    Bukkit.getPluginManager().registerEvents(new LinkWorldEvents(network, node), new com.infernalsuite.asp.InternalPlugin())
}
