package io.linkmc.server

import com.infernalsuite.asp.Converter
import com.infernalsuite.asp.api.world.{SlimeChunk, SlimeWorld}
import com.infernalsuite.asp.api.world.properties.{SlimeProperties, SlimePropertyMap}
import com.infernalsuite.asp.level.chunk.SlimeChunkConverter
import com.infernalsuite.asp.serialization.slime.reader.SlimeWorldReaderRegistry
import net.kyori.adventure.nbt.{BinaryTag, CompoundBinaryTag, ListBinaryTag}
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.{CompoundTag, IntArrayTag, ListTag, NbtIo, Tag}
import net.minecraft.resources.{Identifier, ResourceKey}
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.{ChunkPos, Level}
import net.minecraft.world.level.chunk.storage.{RegionFileStorage, RegionStorageInfo}
import net.minecraft.world.level.storage.LevelResource
import org.bukkit.{Bukkit, Difficulty, Location, World, WorldCreator}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{ArrayList, LinkedHashSet, List, Locale}

/**
 * One-time export of the worlds older Link builds kept in the database (persistent slime worlds and saved data) to
 * vanilla files. Region/entity/POI files and saved data are written before Paper reads any world data; the worlds
 * are then created once (void generator) so Paper writes their level metadata, and spawn/difficulty/PDC are applied.
 * The database copy is kept; a marker prevents a second export.
 */
object LinkWorldExport {
  private final case class Pending(name: String, environment: World.Environment, spawn: Array[Double], difficulty: String, pdc: CompoundTag, open: Boolean)
  private val pending: List[Pending] = new ArrayList[Pending]()
  private var exported = 0; private var chunks = 0; private var skipped = 0

  /** Before world loading: write files. Never overwrites an existing vanilla file. */
  def beforeWorlds(): Unit = {
    val archive = NativeJournalImpl.archive(); if (archive == null) return
    val server = MinecraftServer.getServer()
    val prefix = "native:" + archive.node + "/"
    // Global SavedData (scoreboard, command storage, maps...) goes to the main world's data folder.
    val global = writeSavedData(archive.states, prefix + "saved/global/", server.storageSource.getLevelPath(LevelResource.ROOT).resolve(LevelResource.DATA.id()))
    val open: java.util.Set[String] = new java.util.HashSet[String]()
    val list = archive.states.get(prefix + "worlds")
    if (list != null) java.util.Arrays.stream(new String(list, StandardCharsets.UTF_8).split("\n")).filter(n => !n.isEmpty).forEach(n => { open.add(n); () })
    archive.catalog.forEach((name: String, state: java.lang.Byte) => {
      if (state.byteValue() == 1) {
        try exportWorld(archive, name, open.contains(name))
        catch { case error: Exception => skipped += 1; MinecraftServer.LOGGER.error("Link could not export world {}; its database copy is kept", name, error) }
      }
      ()
    })
    MinecraftServer.LOGGER.info("Link vanilla export: {} world(s), {} chunk(s), {} global saved data file(s), {} skipped", Integer.valueOf(exported), Integer.valueOf(chunks), Integer.valueOf(global), Integer.valueOf(skipped))
  }

  /** After the default worlds loaded: create each exported world once, apply its metadata, then commit the marker. */
  def afterWorlds(): Unit = {
    if (NativeJournalImpl.archive() == null) return
    val names = new LinkedHashSet[String]()
    pending.forEach(p => {
      try {
        val world = Bukkit.createWorld(creator(p.name).environment(p.environment))
        if (world != null) {
          if (p.spawn != null) world.setSpawnLocation(new Location(world, p.spawn(0), p.spawn(1), p.spawn(2), p.spawn(3).toFloat, p.spawn(4).toFloat))
          try world.setDifficulty(Difficulty.valueOf(p.difficulty.toUpperCase(Locale.ROOT))) catch { case ignored: IllegalArgumentException => }
          if (p.pdc != null) world.getPersistentDataContainer().asInstanceOf[org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer].putAll(p.pdc)
          world.save()
          if (p.open) names.add(p.name) else Bukkit.unloadWorld(world, true)
        }
      } catch { case error: Exception => MinecraftServer.LOGGER.error("Link could not open exported world {}", p.name, error) }
      ()
    })
    if (!names.isEmpty()) LinkConfiguration.addAutoload(names)
    pending.clear()
    NativeJournalImpl.markExported("worlds=" + exported + ",chunks=" + chunks + ",skipped=" + skipped)
  }

  /** Opens the configured worlds (vanilla folders under the main world's dimensions). */
  def autoload(names: java.util.Collection[String]): Unit =
    names.forEach(name => {
      if (Bukkit.getWorld(name) == null) {
        try { if (Bukkit.createWorld(creator(name)) == null) MinecraftServer.LOGGER.warn("Link autoload: world {} was not created", name) }
        catch { case error: Exception => MinecraftServer.LOGGER.error("Link autoload: cannot open world {}", name, error) }
      }
      ()
    })

  /** Exported Link worlds were void worlds; keep them void instead of generating terrain around the build. */
  private def creator(name: String): WorldCreator = new WorldCreator(name).generator(new LinkWorldExport.VoidGenerator())
  final class VoidGenerator extends org.bukkit.generator.ChunkGenerator

  private def exportWorld(archive: NativeJournalImpl.Archive, name: String, open: Boolean): Unit = {
    val path = name.toLowerCase(Locale.ROOT).replace(" ", "_")
    if (!Identifier.isValidPath(path)) throw new IllegalArgumentException("World name cannot be a vanilla dimension key: " + name)
    val dimension: ResourceKey[Level] = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(path))
    val folder = MinecraftServer.getServer().storageSource.getDimensionPath(dimension)
    if (Files.exists(folder.resolve("region"))) { MinecraftServer.LOGGER.info("Link export: {} already has vanilla files, skipped", name); return }
    val srf = LinkWorldArchive.restore(name, "native:" + archive.node + "/world/" + name, archive.blobs, archive.states)
    if (srf.isEmpty()) { MinecraftServer.LOGGER.warn("Link export: {} has no committed snapshot, skipped", name); skipped += 1; return }
    val world: SlimeWorld = SlimeWorldReaderRegistry.readWorld(null, name, srf.get(), new SlimePropertyMap(), true)
    val written = writeRegions(world, MinecraftServer.getServer().storageSource.getLevelId(), dimension, folder)
    writeSavedData(archive.states, "native:" + archive.node + "/saved/world/" + name + "/", folder.resolve(LevelResource.DATA.id()))
    val props = world.getPropertyMap()
    val environment = props.getValue(SlimeProperties.ENVIRONMENT).toLowerCase(Locale.ROOT) match {
      case "nether" => World.Environment.NETHER; case "the_end" => World.Environment.THE_END; case _ => World.Environment.NORMAL
    }
    val pdc = world.getExtraData().get("BukkitValues") match { case tag: CompoundBinaryTag => Converter.convertTag[CompoundBinaryTag](tag).asInstanceOf[CompoundTag]; case _ => null }
    pending.add(Pending(name, environment, spawn(world, pdc), props.getValue(SlimeProperties.DIFFICULTY), pdc, open))
    exported += 1; chunks += written
    MinecraftServer.LOGGER.info("Link export: {} -> {} ({} chunks)", name, folder, Integer.valueOf(written))
  }

  /** Template spawn (exact position and view) when the editor set one, otherwise the slime world spawn. */
  private def spawn(world: SlimeWorld, pdc: CompoundTag): Array[Double] = {
    if (pdc != null) {
      val json = pdc.getString("link:template-spawn")
      if (json.isPresent()) {
        try { val v = new com.google.gson.Gson().fromJson(json.get(), classOf[Array[Double]]); if (v != null && v.length == 5 && v.forall(java.lang.Double.isFinite)) return v }
        catch { case ignored: RuntimeException => }
      }
    }
    val p = world.getPropertyMap()
    Array[Double](p.getValue(SlimeProperties.SPAWN_X).intValue() + 0.5, p.getValue(SlimeProperties.SPAWN_Y).doubleValue(), p.getValue(SlimeProperties.SPAWN_Z).intValue() + 0.5, p.getValue(SlimeProperties.SPAWN_YAW).doubleValue(), 0.0)
  }

  private def storage(level: String, dimension: ResourceKey[Level], folder: Path, kind: String, fix: DataFixTypes): RegionFileStorage =
    new RegionFileStorage(new RegionStorageInfo(level, dimension, kind, Array[DataFixTypes](fix)), folder.resolve(if (kind.equals("chunk")) "region" else kind), true)

  /** Writes region/, entities/ and poi/ for one dimension folder; returns the number of chunks. */
  def writeRegions(world: SlimeWorld, level: String, dimension: ResourceKey[Level], folder: Path): Int = {
    val minSection = world.getPropertyMap().getValue(SlimeProperties.CHUNK_SECTION_MIN).intValue()
    val version = world.getDataVersion()
    val region = storage(level, dimension, folder, "chunk", DataFixTypes.CHUNK)
    val entities = storage(level, dimension, folder, "entities", DataFixTypes.ENTITY_CHUNK)
    val poi = storage(level, dimension, folder, "poi", DataFixTypes.POI_CHUNK)
    var count = 0
    try {
      val it = world.getChunkStorage().iterator()
      while (it.hasNext()) {
        val chunk = it.next(); val pos = new ChunkPos(chunk.getX(), chunk.getZ())
        region.write(pos, chunkTag(chunk, minSection, version))
        if (!chunk.getEntities().isEmpty()) entities.write(pos, entityTag(chunk, version))
        if (chunk.getPoiChunkSections() != null && !chunk.getPoiChunkSections().keySet().isEmpty())
          poi.write(pos, SlimeChunkConverter.createPoiChunkFromSlimeSections(chunk.getPoiChunkSections(), version))
        count += 1
      }
    } finally { try region.close() finally { try entities.close() finally poi.close() } }
    count
  }

  private def nms(tag: BinaryTag): Tag = Converter.convertTag[BinaryTag](tag)
  private def list(tags: java.util.List[CompoundBinaryTag]): ListTag = { val out = new ListTag(); tags.forEach(t => { out.add(nms(t)); () }); out }

  /** Inverse of AnvilWorldReader.convertChunk: a fully generated vanilla chunk; light is recomputed on first load. */
  private[server] def chunkTag(chunk: SlimeChunk, minSection: Int, version: Int): CompoundTag = {
    val root = new CompoundTag()
    root.putInt("DataVersion", version)
    root.putInt("xPos", chunk.getX()); root.putInt("zPos", chunk.getZ()); root.putInt("yPos", minSection)
    root.putString("Status", "minecraft:full")
    root.putLong("LastUpdate", 0L); root.putLong("InhabitedTime", 0L)
    val sections = new ListTag(); val source = chunk.getSections(); var i = 0
    while (i < source.length) {
      val section = source(i)
      if (section != null) {
        val tag = new CompoundTag(); tag.putByte("Y", (minSection + i).toByte)
        if (section.getBlockStatesTag() != null) tag.put("block_states", nms(section.getBlockStatesTag()))
        if (section.getBiomeTag() != null) tag.put("biomes", nms(section.getBiomeTag()))
        if (section.getBlockLight() != null) tag.putByteArray("BlockLight", section.getBlockLight().getBacking().clone())
        if (section.getSkyLight() != null) tag.putByteArray("SkyLight", section.getSkyLight().getBacking().clone())
        sections.add(tag)
      }
      i += 1
    }
    root.put("sections", sections)
    if (chunk.getHeightMaps() != null) root.put("Heightmaps", nms(chunk.getHeightMaps()))
    root.put("block_entities", list(chunk.getTileEntities()))
    if (chunk.getBlockTicks() != null) root.put("block_ticks", nms(chunk.getBlockTicks()))
    if (chunk.getFluidTicks() != null) root.put("fluid_ticks", nms(chunk.getFluidTicks()))
    val pdc = chunk.getExtraData().get("ChunkBukkitValues")
    if (pdc != null) root.put("ChunkBukkitValues", nms(pdc))
    root
  }

  private[server] def entityTag(chunk: SlimeChunk, version: Int): CompoundTag = {
    val root = new CompoundTag()
    root.putInt("DataVersion", version)
    root.put("Position", new IntArrayTag(Array[Int](chunk.getX(), chunk.getZ())))
    root.put("Entities", list(chunk.getEntities()))
    root
  }

  /** Each committed SavedData value is already the vanilla file content ({data, DataVersion}). */
  private def writeSavedData(states: java.util.Map[String, Array[Byte]], prefix: String, folder: Path): Int = {
    var count = 0
    states.forEach((key: String, value: Array[Byte]) => {
      if (key.startsWith(prefix) && value.length > 0) {
        val id = key.substring(prefix.length())
        try {
          val file = Identifier.parse(id).withSuffix(".dat").resolveAgainst(folder)
          if (file.toAbsolutePath().normalize().startsWith(folder.toAbsolutePath().normalize()) && !Files.exists(file)) {
            Files.createDirectories(file.getParent()); NbtIo.writeCompressed(NativePlayerCodec.decode(value), file); count += 1
          }
        } catch { case error: Exception => MinecraftServer.LOGGER.warn("Link export: saved data {} skipped", key, error) }
      }
      ()
    })
    count
  }
}
