package io.linkmc.core

import io.linkmc.api.LinkApi
import io.linkmc.api.LinkApi.{Destination, WorldInfo, Worlds}

import java.util.List
import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, CompletionException, CompletionStage, ExecutorService, RejectedExecutionException, ThreadPoolExecutor, TimeUnit}

/** Serializes lifecycle operations and delegates all live-world work to a tick-thread driver. */
final class WorldService(driver: WorldService.Driver, templateStore: TemplateStore) extends Worlds with AutoCloseable {
  private val worker: ExecutorService = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](128), Thread.ofPlatform().name("Link-Worlds-", 0).factory(), new ThreadPoolExecutor.AbortPolicy())

  private def run[T](task: WorldService.Task[T]): CompletionStage[T] =
    try CompletableFuture.supplyAsync[T](() => { try task.run() catch { case e: Exception => throw new CompletionException(e) } }, worker)
    catch { case e: RejectedExecutionException => CompletableFuture.failedFuture[T](e) }

  override def templates(): CompletionStage[List[String]] = run[List[String]](() => templateStore.list())
  override def spawn(name: String): CompletionStage[Destination] = { LinkApi.requireName(name); run[Destination](() => driver.spawn(name)) }
  override def createEditor(name: String): CompletionStage[WorldInfo] = { LinkApi.requireName(name); run[WorldInfo](() => driver.createEditor(name)) }
  override def publish(editor: String, template: String, version: Int): CompletionStage[Void] = {
    LinkApi.requireName(editor); LinkApi.requireName(template); WorldService.version(version)
    run[Void](() => { val publication = driver.publication(editor); templateStore.publish(template, version, publication.bytes, publication.policy); null })
  }
  override def create(name: String, template: String, version: Int, persistent: Boolean): CompletionStage[WorldInfo] = {
    LinkApi.requireName(name); LinkApi.requireName(template); WorldService.version(version)
    run[WorldInfo](() => driver.instantiate(name, templateStore.read(template, version), persistent))
  }
  override def ensurePersistent(name: String, template: String, version: Int): CompletionStage[WorldInfo] = {
    LinkApi.requireName(name); LinkApi.requireName(template); WorldService.version(version)
    run[WorldInfo](() => {
      val live = driver.loaded().stream().filter((w: WorldInfo) => w.name().equals(name)).findFirst()
      if (live.isPresent()) live.get() else if (driver.exists(name)) driver.load(name) else driver.instantiate(name, templateStore.read(template, version), true)
    })
  }
  override def load(name: String): CompletionStage[WorldInfo] = run[WorldInfo](() => driver.load(name))
  override def save(name: String): CompletionStage[Void] = run[Void](() => { driver.save(name); null })
  override def unload(name: String): CompletionStage[Void] = run[Void](() => { driver.unload(name); null })
  override def delete(name: String): CompletionStage[Void] = run[Void](() => { driver.delete(name); null })
  override def loaded(): CompletionStage[List[WorldInfo]] = run[List[WorldInfo]](() => List.copyOf(driver.loaded()))
  override def close(): Unit = {
    worker.shutdownNow()
    try {
      if (!worker.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("World operations did not stop; do not release ownership")
    } catch { case e: InterruptedException => Thread.currentThread().interrupt(); throw new IllegalStateException("World shutdown interrupted", e) }
  }
}

object WorldService {
  final case class Publication(bytes: Array[Byte], policy: LinkApi.ChannelPolicy)
  trait Driver {
    @throws[Exception]
    def publication(name: String): Publication = new Publication(snapshot(name), channelPolicy(name))
    @throws[Exception]
    def exists(name: String): Boolean = throw new UnsupportedOperationException()
    @throws[Exception]
    def channelPolicy(name: String): LinkApi.ChannelPolicy = LinkApi.ChannelPolicy.defaults()
    @throws[Exception]
    def spawn(name: String): Destination = throw new UnsupportedOperationException()
    @throws[Exception]
    def createEditor(name: String): WorldInfo
    /** Must obtain a stable snapshot, not concurrently serialize a live mutable world. */
    @throws[Exception]
    def snapshot(name: String): Array[Byte]
    @throws[Exception]
    def instantiate(name: String, srf: Array[Byte], persistent: Boolean): WorldInfo
    @throws[Exception]
    def load(name: String): WorldInfo
    @throws[Exception]
    def save(name: String): Unit
    @throws[Exception]
    def unload(name: String): Unit
    @throws[Exception]
    def delete(name: String): Unit
    @throws[Exception]
    def loaded(): List[WorldInfo]
  }
  @FunctionalInterface private trait Task[T] {
    @throws[Exception]
    def run(): T
  }
  private def version(version: Int): Unit = if (version < 1) throw new IllegalArgumentException("Template version must be positive")
}
