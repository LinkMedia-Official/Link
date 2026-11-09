package io.linkmc.server

import io.linkmc.api.LinkApi.Destination
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.SavedDataStorage

import java.nio.file.Path
import java.util.{Optional, UUID}
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier

/** ServiceLoader provider for {@link LinkServerBridge}: the Java entry points forward here. Pure delegation. */
final class LinkServerImpl extends LinkServerBridge {
  // NativeJournal
  override def journalEnabled(): Boolean = NativeJournalImpl.enabled()
  override def run(action: Runnable): Unit = NativeJournalImpl.run(action)
  override def runTask(task: BooleanSupplier): Boolean = NativeJournalImpl.runTask(task)
  override def moving(player: UUID): Boolean = NativeJournalImpl.moving(player)
  override def initializeEarly(): Unit = NativeJournalImpl.initializeEarly()
  override def prefetchArrival(player: UUID, connection: Connection): Unit = NativeJournalImpl.prefetchArrival(player, connection)
  override def arrivalReady(player: UUID, connection: Connection): Boolean = NativeJournalImpl.arrivalReady(player, connection)
  override def prepare(player: UUID, connection: Connection): Unit = NativeJournalImpl.prepare(player, connection)
  override def destination(id: UUID): Destination = NativeJournalImpl.destination(id)
  override def playerData(id: UUID): Optional[CompoundTag] = NativeJournalImpl.playerData(id)
  override def readPlayerData(id: UUID): Optional[CompoundTag] = NativeJournalImpl.readPlayerData(id)
  override def joined(player: ServerPlayer): Unit = NativeJournalImpl.joined(player)
  override def left(player: ServerPlayer): Unit = NativeJournalImpl.left(player)
  override def stats(id: UUID): com.google.gson.JsonElement = NativeJournalImpl.stats(id)
  override def advancements(id: UUID): com.google.gson.JsonElement = NativeJournalImpl.advancements(id)
  override def savedData(storage: SavedDataStorage, path: Path, id: String): Optional[CompoundTag] = NativeJournalImpl.savedData(storage, path, id)
  override def seedStorage(world: String, seed: CompoundTag): Unit = NativeJournalImpl.seedStorage(world, seed)
  override def registerStorage(storage: SavedDataStorage, path: Path): Unit = NativeJournalImpl.registerStorage(storage, path)
  override def saveAsync(): CompletableFuture[Void] = NativeJournalImpl.saveAsync()
  // LinkRuntime
  override def beforeWorlds(): Unit = LinkRuntimeImpl.beforeWorlds()
  override def afterWorlds(): Unit = LinkRuntimeImpl.afterWorlds()
  override def beginShutdown(): Unit = LinkRuntimeImpl.beginShutdown()
  override def stop(): Unit = LinkRuntimeImpl.stop()
  override def managesWorlds(): Boolean = LinkRuntimeImpl.managesWorlds()
}
