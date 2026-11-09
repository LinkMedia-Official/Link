package io.linkmc.core

import io.linkmc.api.LinkApi
import io.linkmc.api.LinkApi.{Channel, Destination, WorldInfo, Worlds}

import java.util.UUID
import java.util.concurrent.{CompletableFuture, CompletionException, CompletionStage}
import java.util.concurrent.atomic.AtomicBoolean

/** One provisioner per backend; publishes readiness only after world creation succeeds. */
final class SpawnProvisioner(worlds: Worlds, network: NetworkStore, node: String, template: String, version: Int, capacity: Int, maxWorlds: Int) {
  private val creating = new AtomicBoolean()
  LinkApi.requireName(node); LinkApi.requireName(template)
  if (version < 1 || capacity < 1 || maxWorlds < 1) throw new IllegalArgumentException("Invalid spawn limits")

  /** Call on a background control executor, not a tick thread. No unbounded world creation. */
  def ensureSpare(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): CompletionStage[java.lang.Boolean] =
    ensureSpare((name: String) => CompletableFuture.completedFuture[Destination](new Destination(node, name, x, y, z, yaw, pitch)))
  def ensureSpare(): CompletionStage[java.lang.Boolean] = ensureSpare((name: String) => worlds.spawn(name))
  private def ensureSpare(spawn: java.util.function.Function[String, CompletionStage[Destination]]): CompletionStage[java.lang.Boolean] = {
    if (!creating.compareAndSet(false, true)) return CompletableFuture.completedFuture[java.lang.Boolean](java.lang.Boolean.FALSE)
    try {
      val local = network.spawnChannels().stream().filter((c: Channel) => c.node().equals(node)).toList()
      if (network.channelCount(node) >= maxWorlds || local.stream().anyMatch((c: Channel) => c.occupied() + c.reserved() == 0)) {
        creating.set(false); CompletableFuture.completedFuture[java.lang.Boolean](java.lang.Boolean.FALSE)
      } else {
        val name = "spawn-" + UUID.randomUUID()
        worlds.create(name, template, version, false).thenCompose[Destination]((info: WorldInfo) => spawn.apply(name)).thenApplyAsync[java.lang.Boolean]((destination: Destination) => {
          try { network.register(node + ":" + name, destination, capacity); java.lang.Boolean.TRUE }
          catch { case e: Exception => worlds.unload(name); throw new CompletionException(e) }
        }).whenComplete((v: java.lang.Boolean, e: Throwable) => creating.set(false))
      }
    } catch { case e: Exception => creating.set(false); CompletableFuture.failedFuture[java.lang.Boolean](e) }
  }
}
