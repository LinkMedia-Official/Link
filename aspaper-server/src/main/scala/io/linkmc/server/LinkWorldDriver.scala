package io.linkmc.server

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI
import com.infernalsuite.asp.api.SlimeNMSBridge
import com.infernalsuite.asp.api.world.SlimeWorld
import com.infernalsuite.asp.api.world.SlimeWorldInstance
import com.infernalsuite.asp.api.world.properties.SlimeProperties
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap
import io.linkmc.api.LinkApi.{ChannelPolicy, Change, Destination, Session, State, Transaction, WorldInfo}
import io.linkmc.core.{ConflictException, EventStore, NetworkStore, WorldBlobs, WorldService}
import net.minecraft.server.MinecraftServer
import org.bukkit.Bukkit
import org.bukkit.World

import java.util.{List, Map, Objects, UUID}
import java.util.concurrent.{Callable, CompletableFuture, ConcurrentHashMap}

/** Native server adapter. Snapshot operations always capture on the server thread. */
final class LinkWorldDriver(private val store: EventStore, private val guard: Session, private val node: String, private val blobs: WorldBlobs, private val network: NetworkStore) extends WorldService.Driver {
  import LinkWorldDriver.*

  private val asp: AdvancedSlimePaperAPI = AdvancedSlimePaperAPI.instance()
  private val managed: Map[String, java.lang.Boolean] = new ConcurrentHashMap[String, java.lang.Boolean]()
  private val versions: Map[String, java.lang.Long] = new ConcurrentHashMap[String, java.lang.Long]()
  private[server] def editableNames(): List[String] = managed.entrySet().stream().filter((e: Map.Entry[String, java.lang.Boolean]) => e.getValue().booleanValue()).map[String]((e: Map.Entry[String, java.lang.Boolean]) => e.getKey()).toList()
  private[server] def loadedNames(): List[String] = List.copyOf(managed.keySet())
  private def key(name: String): String = { io.linkmc.api.LinkApi.requireName(name); "world:" + node + "/" + name }

  private def live(name: String): SlimeWorldInstance = {
    if (!managed.containsKey(name)) throw new ConflictException("Not a Link-managed world: " + name)
    val world: World = Bukkit.getWorld(name)
    if (world == null) throw new ConflictException("World is not loaded")
    Objects.requireNonNull(SlimeNMSBridge.instance().getInstance(world))
  }
  @throws[Exception]
  def bootstrap(name: String, template: Array[Byte]): SlimeWorld = {
    val world: SlimeWorld = asp.getSerializer().deserializeWorld(name, template, null, properties(), true)
    managed.put(name, java.lang.Boolean.FALSE); world
  }
  @throws[Exception]
  def restoreNative(name: String, bytes: Array[Byte]): Unit = {
    val world = asp.getSerializer().deserializeWorld(name, bytes, null, properties(), true)
    asp.loadWorld(world, true); managed.put(name, java.lang.Boolean.valueOf(NativeJournalImpl.persistentWorld(name)))
  }
  private var newBootstrapEditor: Boolean = false
  @throws[Exception]
  def bootstrapEditor(name: String): SlimeWorld = {
    var saved: Array[Byte] = null
    if (NativeJournalImpl.enabled()) {
      if (NativeJournalImpl.knownWorld(name)) saved = NativeJournalImpl.storedWorld(name)
      else NativeJournalImpl.createdWorld(name, true)
    } else {
      val state = store.read(key(name))
      if (state.isPresent()) {
        if (state.get().payload().length == 0) throw new ConflictException("Bootstrap editor was deleted")
        saved = blobs.decode(state.get().payload()); versions.put(name, java.lang.Long.valueOf(state.get().version()))
      } else versions.put(name, java.lang.Long.valueOf(0L))
    }
    newBootstrapEditor = saved == null
    managed.put(name, java.lang.Boolean.TRUE)
    if (saved == null) asp.createEmptyWorld(name, true, properties(), null)
    else asp.getSerializer().deserializeWorld(name, saved, null, properties(), true)
  }
  def prepareBootstrapEditor(name: String): Unit = {
    val world = Bukkit.getWorld(name)
    world.setAutoSave(false)
    if (newBootstrapEditor) {
      world.getWorldBorder().setSize(256)
      var x = -4
      while (x <= 4) {
        var z = -4
        while (z <= 4) { world.getBlockAt(x, 64, z).setType(org.bukkit.Material.BEDROCK); z += 1 }
        x += 1
      }
      world.setSpawnLocation(0, 65, 0)
    }
  }
  @throws[Exception]
  override def createEditor(name: String): WorldInfo = {
    key(name)
    val stored = store.read(key(name))
    if (stored.isPresent()) throw ConflictException.worldExists(name, stored.get().payload().length == 0)
    main[WorldInfo](() => {
      NativeJournalImpl.requireNewWorld(name)
      val world: SlimeWorld = asp.createEmptyWorld(name, true, properties(), null)
      val loaded: SlimeWorldInstance = asp.loadWorld(world, true)
      managed.put(name, java.lang.Boolean.TRUE); versions.put(name, java.lang.Long.valueOf(0L))
      NativeJournalImpl.createdWorld(name, true)
      loaded.getBukkitWorld().setAutoSave(false)
      loaded.getBukkitWorld().getWorldBorder().setSize(256)
      var x = -4
      while (x <= 4) {
        var z = -4
        while (z <= 4) { loaded.getBukkitWorld().getBlockAt(x, 64, z).setType(org.bukkit.Material.BEDROCK); z += 1 }
        x += 1
      }
      loaded.getBukkitWorld().setSpawnLocation(0, 65, 0)
      new WorldInfo(name, true, 0)
    })
  }
  @throws[Exception]
  private def capture(name: String): Captured = {
    key(name)
    readMain[Captured](() => {
      val result = LinkChunkSnapshots.freeze(live(name).asInstanceOf[SlimeWorld])
      if (NativeJournalImpl.enabled()) {
        val saved = new net.minecraft.nbt.CompoundTag()
        Bukkit.getWorld(name).asInstanceOf[org.bukkit.craftbukkit.CraftWorld].getHandle().getDataStorage().linkSnapshot().forEach((id: String, tag: net.minecraft.nbt.CompoundTag) => {
          if (!id.equals("paper:metadata")) saved.put(id, tag)
        })
        result.getExtraData().put("LinkSavedData", com.infernalsuite.asp.Converter.convertTag[net.kyori.adventure.nbt.BinaryTag](saved))
      }
      new Captured(result, LinkTemplateGui.policy(Bukkit.getWorld(name)))
    })
  }
  @throws[Exception]
  override def snapshot(name: String): Array[Byte] = asp.getSerializer().serializeWorld(capture(name).world)
  @throws[Exception]
  override def publication(name: String): WorldService.Publication = { val copy = capture(name); new WorldService.Publication(asp.getSerializer().serializeWorld(copy.world), copy.policy) }
  @throws[Exception]
  override def instantiate(name: String, srf: Array[Byte], persistent: Boolean): WorldInfo = {
    key(name)
    val stored = store.read(key(name))
    if (stored.isPresent()) throw ConflictException.worldExists(name, stored.get().payload().length == 0)
    val world: SlimeWorld = asp.getSerializer().deserializeWorld(name, srf, null, properties(), true)
    val result: WorldInfo = main[WorldInfo](() => {
      NativeJournalImpl.requireNewWorld(name)
      val instance = asp.loadWorld(world, true); managed.put(name, java.lang.Boolean.valueOf(persistent)); versions.put(name, java.lang.Long.valueOf(0L))
      NativeJournalImpl.createdWorld(name, persistent)
      instance.getBukkitWorld().setAutoSave(false)
      new WorldInfo(name, persistent, instance.getBukkitWorld().getPlayerCount())
    })
    if (persistent) save(name)
    result
  }
  @throws[Exception]
  override def load(name: String): WorldInfo = {
    key(name)
    if (NativeJournalImpl.enabled()) {
      if (Bukkit.isPrimaryThread()) return loadOnServerThread(name)
      // Copy the committed entries on the server thread, then download/decode here (off the server thread).
      val view: Map[String, Array[Byte]] = idleMain[Map[String, Array[Byte]]](() => {
        if (managed.containsKey(name)) throw new ConflictException("World already loaded")
        NativeJournalImpl.committedWorldView(name)
      })
      val decoded: SlimeWorld = asp.getSerializer().deserializeWorld(name, NativeJournalImpl.decodeWorld(name, view), null, properties(), true)
      val info: WorldInfo = main[WorldInfo](() => {
        if (managed.containsKey(name)) throw new ConflictException("World already loaded")
        if (!NativeJournalImpl.storedWorldExists(name)) throw new ConflictException("Unknown or deleted world")
        asp.loadWorld(decoded, true); managed.put(name, java.lang.Boolean.valueOf(NativeJournalImpl.persistentWorld(name)))
        Bukkit.getWorld(name).setAutoSave(false); new WorldInfo(name, NativeJournalImpl.persistentWorld(name), 0)
      })
      network.openWarpWorld(node, name); return info
    }
    loadOnServerThread(name)
  }
  @throws[Exception]
  private def loadOnServerThread(name: String): WorldInfo = {
    if (NativeJournalImpl.enabled()) {
      val info: WorldInfo = main[WorldInfo](() => {
        if (managed.containsKey(name)) throw new ConflictException("World already loaded")
        val world = asp.getSerializer().deserializeWorld(name, NativeJournalImpl.storedWorld(name), null, properties(), true)
        asp.loadWorld(world, true); managed.put(name, java.lang.Boolean.valueOf(NativeJournalImpl.persistentWorld(name)))
        Bukkit.getWorld(name).setAutoSave(false); new WorldInfo(name, NativeJournalImpl.persistentWorld(name), 0)
      })
      network.openWarpWorld(node, name); return info
    }
    val state: State = store.read(key(name)).orElseThrow[ConflictException](() => new ConflictException("Unknown stored world"))
    if (state.payload().length == 0) throw new ConflictException("World was deleted")
    val world: SlimeWorld = asp.getSerializer().deserializeWorld(name, blobs.decode(state.payload()), null, properties(), true)
    main[WorldInfo](() => {
      asp.loadWorld(world, true); managed.put(name, java.lang.Boolean.TRUE); versions.put(name, java.lang.Long.valueOf(state.version()))
      Bukkit.getWorld(name).setAutoSave(false); new WorldInfo(name, true, 0)
    })
  }
  @throws[Exception]
  override def save(name: String): Unit = {
    key(name)
    if (NativeJournalImpl.enabled()) {
      main[AnyRef](() => {
        live(name)
        if (!java.lang.Boolean.TRUE.equals(managed.get(name))) throw new ConflictException("Temporary worlds are never saved")
        NativeJournalImpl.run(() => ()); null
      })
      return
    }
    if (!java.lang.Boolean.TRUE.equals(managed.get(name))) throw new ConflictException("Temporary worlds are never saved")
    val bytes: Array[Byte] = snapshot(name)
    val version: Long = versions.get(name).longValue()
    store.commit(new Transaction(UUID.randomUUID(), List.of(guard), List.of(new Change(key(name), version, 1, "world:checkpoint", blobs.encode(key(name), bytes)))))
    versions.put(name, java.lang.Long.valueOf(version + 1))
  }
  @throws[Exception]
  override def unload(name: String): Unit = {
    key(name)
    main[AnyRef](() => {
      val world: World = live(name).getBukkitWorld()
      if (world.equals(Bukkit.getWorlds().getFirst())) throw new ConflictException("Cannot unload the bootstrap world")
      if (!world.getPlayers().isEmpty()) throw new ConflictException("World still has players")
      null
    })
    if (java.lang.Boolean.TRUE.equals(managed.get(name))) save(name)
    if (NativeJournalImpl.enabled()) network.closeWorld(node, name)
    main[AnyRef](() => {
      if (!Bukkit.unloadWorld(name, false)) throw new ConflictException("World unload rejected")
      managed.remove(name); versions.remove(name); null
    })
  }
  @throws[Exception]
  override def delete(name: String): Unit = {
    key(name)
    if (managed.containsKey(name)) throw new ConflictException("Unload the world before deleting it")
    if (NativeJournalImpl.enabled()) { main[AnyRef](() => { NativeJournalImpl.deleteWorld(name); null }); return }
    val state: State = store.read(key(name)).orElseThrow[ConflictException](() => new ConflictException("Unknown world"))
    store.commit(new Transaction(UUID.randomUUID(), List.of(guard), List.of(new Change(key(name), state.version(), 1, "world:deleted", new Array[Byte](0)))))
  }
  @throws[Exception]
  override def loaded(): List[WorldInfo] =
    readMain[List[WorldInfo]](() => managed.entrySet().stream().map[WorldInfo]((e: Map.Entry[String, java.lang.Boolean]) => new WorldInfo(e.getKey(), e.getValue().booleanValue(), live(e.getKey()).getBukkitWorld().getPlayerCount())).toList())
  @throws[Exception]
  override def exists(name: String): Boolean =
    if (NativeJournalImpl.enabled()) readMain[java.lang.Boolean](() => java.lang.Boolean.valueOf(NativeJournalImpl.knownWorld(name))).booleanValue() else store.read(key(name)).isPresent()
  @throws[Exception]
  override def channelPolicy(name: String): ChannelPolicy = readMain[ChannelPolicy](() => LinkTemplateGui.policy(live(name).getBukkitWorld()))
  @throws[Exception]
  override def spawn(name: String): Destination =
    readMain[Destination](() => { val pos = LinkTemplateGui.spawn(live(name).getBukkitWorld()); new Destination(node, name, pos.getX(), pos.getY(), pos.getZ(), pos.getYaw(), pos.getPitch()) })
  @throws[Exception]
  def saveOnShutdown(): Unit = {
    val names = List.copyOf(managed.keySet()).iterator()
    while (names.hasNext()) { val name = names.next(); if (java.lang.Boolean.TRUE.equals(managed.get(name))) save(name) }
  }
}

object LinkWorldDriver {
  @throws[Exception]
  private[server] def main[T](task: Callable[T]): T =
    main(task, true)
  @throws[Exception]
  private[server] def readMain[T](task: Callable[T]): T = main(task, false, false)
  /** Server-thread read of committed journal state: waits (without blocking the tick) for an in-flight checkpoint. */
  @throws[Exception]
  private[server] def idleMain[T](task: Callable[T]): T = main(task, false, true)
  @throws[Exception]
  private def main[T](task: Callable[T], durable: Boolean): T = main(task, durable, durable)
  @throws[Exception]
  private def main[T](task: Callable[T], durable: Boolean, idle: Boolean): T = {
    if (Bukkit.isPrimaryThread()) return task.call()
    if (MinecraftServer.getServer().isStopped()) throw new IllegalStateException("Server stopped before world operation could run")
    val future = new CompletableFuture[T]()
    if (!NativeJournalImpl.watch(future)) return future.get()
    val body: Runnable = () => {
      if (!future.isCancelled()) {
        try { val value: T = task.call(); if (durable) NativeJournalImpl.complete[T](future, value) else future.complete(value) }
        catch {
          case e: Throwable =>
            future.completeExceptionally(e)
            if (NativeJournalImpl.enabled() && !e.isInstanceOf[ConflictException] && !e.isInstanceOf[IllegalArgumentException]) throw new IllegalStateException("World operation failed", e)
        }
      }
    }
    // World mutations run once no checkpoint is in flight, so they never make the tick wait for storage.
    MinecraftServer.getServer().execute(if (idle) ((() => NativeJournalImpl.whenIdle(body)): Runnable) else body)
    // No timeout: otherwise a timed-out operation might still create a world later.
    try future.get()
    catch { case e: InterruptedException => future.cancel(false); Thread.currentThread().interrupt(); throw e }
  }
  private def properties(): SlimePropertyMap = {
    val props = new SlimePropertyMap()
    props.setValue(SlimeProperties.SAVE_BLOCK_TICKS, java.lang.Boolean.valueOf(true))
    props.setValue(SlimeProperties.SAVE_FLUID_TICKS, java.lang.Boolean.valueOf(true))
    props.setValue(SlimeProperties.SAVE_POI, java.lang.Boolean.valueOf(true))
    props.setValue(SlimeProperties.SPAWN_Y, Integer.valueOf(65))
    props
  }
  private final case class Captured(world: SlimeWorld, policy: ChannelPolicy)
}
