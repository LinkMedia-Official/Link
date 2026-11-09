package io.linkmc.server

import com.infernalsuite.asp.Converter
import com.infernalsuite.asp.serialization.slime.reader.SlimeWorldReaderRegistry
import com.infernalsuite.asp.api.world.{SlimeChunk, SlimeChunkSection, SlimeWorld}
import com.infernalsuite.asp.api.world.properties.{SlimeProperties, SlimePropertyMap}
import com.infernalsuite.asp.level.SlimeInMemoryWorld
import com.infernalsuite.asp.level.chunk.{NMSSlimeChunk, PartiallySerializedSlimeChunk}
import com.infernalsuite.asp.serialization.slime.SlimeSerializer
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld
import com.infernalsuite.asp.util.Util
import io.linkmc.core.{AsyncSavePipeline, BlobSnapshots, WorldBlobs}
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.kyori.adventure.nbt.BinaryTag

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.{ArrayList, Collections, HashMap, HashSet, HexFormat, List, Map, Optional, Set}
import java.util.concurrent.ConcurrentHashMap

/** Content-detected chunk checkpoints. All parts and the index commit in the same native frame. */
final class LinkChunkSnapshots {
  import LinkChunkSnapshots.*

  private val captured: Map[String, Cached] = new ConcurrentHashMap[String, Cached]()

  // ── Incremental server-thread capture ───────────────────────────────────────────────────────────────
  private val worlds: Map[String, WorldState] = new HashMap[String, WorldState]()

  /**
   * Server thread. Copies only chunks that may have changed since the last committed capture; every other chunk is
   * carried over from the committed snapshot. A chunk is re-captured when ANY of these holds:
   * its Link change counter moved (every vanilla block/block-entity/heightmap/biome change goes through
   * ChunkAccess.markUnsaved), a neighbour's counter moved (light crosses chunk borders), it holds entities, block
   * entities, pending block/fluid ticks or chunk PDC (state that can change without that counter), its chunk
   * object changed (load/unload), it was never captured, it is in this round's rotating sweep, or {@code full}.
   */
  private[server] def freezeIncremental(live: SlimeWorld, full: Boolean): Frozen = {
    val state = worlds.computeIfAbsent(live.getName(), (k: String) => new WorldState())
    val metadata = shell(live, true)
    val ticks: Boolean = metadata.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS).booleanValue()
    val fluids: Boolean = metadata.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS).booleanValue()
    val poi: Boolean = metadata.getPropertyMap().getValue(SlimeProperties.SAVE_POI).booleanValue()
    val sources: List[SlimeChunk] = new ArrayList[SlimeChunk](live.getChunkStorage())
    // blocks: full copy (sections, light, heightmaps...). volatileOnly: blocks unchanged, re-capture the rest.
    val blocks: Set[java.lang.Long] = new HashSet[java.lang.Long]()
    val volatileOnly: Set[java.lang.Long] = new HashSet[java.lang.Long]()
    val volatileAll: Set[java.lang.Long] = new HashSet[java.lang.Long]()
    val present: Set[java.lang.Long] = new HashSet[java.lang.Long]()
    val modifiedArea: Set[java.lang.Long] = new HashSet[java.lang.Long]()
    val modified: List[Array[Int]] = new ArrayList[Array[Int]]()
    val cursor = state.sweepCursor; state.sweepCursor += 1
    val round: Int = Math.floorMod(cursor, SWEEP_ROUNDS.toLong).toInt
    val first = sources.iterator()
    while (first.hasNext()) {
      val source = first.next()
      val position: Long = Util.chunkPosition(source.getX(), source.getZ()); present.add(java.lang.Long.valueOf(position))
      val seen: Seen = state.seen.get(java.lang.Long.valueOf(position))
      // Deterministic by position: every chunk falls in exactly one of the SWEEP_ROUNDS rounds.
      val swept: Boolean = Math.floorMod(java.lang.Long.hashCode(position * 0x9E3779B97F4A7C15L), SWEEP_ROUNDS) == round
      source match {
        case nms: NMSSlimeChunk =>
          val chunk = nms.getChunk()
          val changed: Boolean = seen == null || (seen.identity ne chunk) || seen.modCount != chunk.linkModCount
          if (changed) modified.add(Array[Int](source.getX(), source.getZ()))
          val volatileNow: Boolean = volatileState(chunk)
          if (volatileNow) volatileAll.add(java.lang.Long.valueOf(position))
          if (full || swept || changed || state.settling.contains(java.lang.Long.valueOf(position))) blocks.add(java.lang.Long.valueOf(position))
          else if (volatileNow) volatileOnly.add(java.lang.Long.valueOf(position))
        case _ =>
          if (full || swept || seen == null || (seen.identity ne source)) blocks.add(java.lang.Long.valueOf(position))
      }
    }
    // Light crosses chunk borders: neighbours of modified chunks are copied in full, this round and the next.
    val around = modified.iterator()
    while (around.hasNext()) {
      val at = around.next()
      var dx = -1
      while (dx <= 1) {
        var dz = -1
        while (dz <= 1) {
          val n: Long = Util.chunkPosition(at(0) + dx, at(1) + dz)
          if (present.contains(java.lang.Long.valueOf(n))) { blocks.add(java.lang.Long.valueOf(n)); modifiedArea.add(java.lang.Long.valueOf(n)) }
          dz += 1
        }
        dx += 1
      }
    }
    state.settling = modifiedArea
    val chunks = new Long2ObjectOpenHashMap[SlimeChunk](); val carried: Set[String] = new HashSet[String]()
    val second = sources.iterator()
    while (second.hasNext()) {
      val source = second.next()
      val position: Long = Util.chunkPosition(source.getX(), source.getZ())
      val whole = blocks.contains(java.lang.Long.valueOf(position))
      val refresh = !whole && volatileOnly.contains(java.lang.Long.valueOf(position)) && source.isInstanceOf[NMSSlimeChunk]
      if (!whole && !refresh) carried.add(s"${source.getX()}_${source.getZ()}")
      else {
        val copy: SlimeChunk =
          if (whole) LinkChunkSnapshots.copy(source, ticks, fluids, poi)
          else {
            val nms = source.asInstanceOf[NMSSlimeChunk]
            val last = state.lastCopy.get(java.lang.Long.valueOf(position))
            if (last == null) LinkChunkSnapshots.copy(source, ticks, fluids, poi) else PartiallySerializedSlimeChunk.refreshVolatile(last, nms, ticks, fluids, poi)
          }
        chunks.put(position, copy)
        // Keep a reusable copy only where it pays off (chunks with entities/block entities/ticks/PDC/POI).
        copy match {
          case partial: PartiallySerializedSlimeChunk if volatileAll.contains(java.lang.Long.valueOf(position)) => state.lastCopy.put(java.lang.Long.valueOf(position), partial)
          case _ => state.lastCopy.remove(java.lang.Long.valueOf(position))
        }
        val identity: AnyRef = source match { case nms: NMSSlimeChunk => nms.getChunk(); case _ => source }
        val mod: Long = source match { case nms: NMSSlimeChunk => nms.getChunk().linkModCount; case _ => 0L }
        state.seen.put(java.lang.Long.valueOf(position), new Seen(identity, mod))
      }
    }
    state.seen.keySet().retainAll(present); state.lastCopy.keySet().retainAll(present)
    new Frozen(new SkeletonSlimeWorld(metadata.getName(), null, true, chunks, metadata.getExtraData(), metadata.getPropertyMap(), metadata.getDataVersion()), carried)
  }

  /** Forget all change tracking: the next capture is complete. Used after a checkpoint did not commit. */
  private[server] def invalidate(): Unit = { worlds.clear(); captured.clear() }
  private[server] def forgetWorld(name: String): Unit = worlds.remove(name)

  @throws[Exception]
  private[server] def capture(world: SlimeWorld, legacyKey: String, blobs: WorldBlobs, previous: Map[String, Array[Byte]], updates: Map[String, Array[Byte]]): Unit =
    capture(world, legacyKey, blobs, previous, updates, null)
  @throws[Exception]
  private[server] def capture(world: SlimeWorld, legacyKey: String, blobs: WorldBlobs, previous: Map[String, Array[Byte]], updates: Map[String, Array[Byte]], pipeline: AsyncSavePipeline): Unit =
    capture(world, legacyKey, blobs, previous, updates, pipeline, null)
  @throws[Exception]
  private[server] def capture(world: SlimeWorld, legacyKey: String, blobs: WorldBlobs, previous: Map[String, Array[Byte]], updates: Map[String, Array[Byte]], pipeline: AsyncSavePipeline, progress: io.linkmc.core.SaveProgress): Unit =
    capture(world, legacyKey, blobs, previous, updates, pipeline, progress, Set.of[String]())
  /** {@code carried}: coordinates not re-captured this round; their committed snapshot stays in the index unchanged. */
  @throws[Exception]
  private[server] def capture(world: SlimeWorld, legacyKey: String, blobs: WorldBlobs, previous: Map[String, Array[Byte]], updates: Map[String, Array[Byte]], pipeline: AsyncSavePipeline, progress: io.linkmc.core.SaveProgress, carried: Set[String]): Unit = {
    if (progress != null) progress.phase("world-metadata:" + world.getName(), 1)
    val root = key(legacyKey)
    val metadata = shell(world, true)
    val metaBytes: Array[Byte] = SlimeSerializer.serialize(metadata, new Array[Byte](4))
    val metaHash: Array[Byte] = hash(metaBytes)
    val oldMeta: Cached = captured.get(root + "/meta")
    if (oldMeta == null || !MessageDigest.isEqual(oldMeta.digest, metaHash)) {
      put(root + "/meta", metaBytes, blobs, previous, updates, oldMeta == null)
      captured.put(root + "/meta", new Cached(metaHash))
    }
    val coordinates: List[String] = new ArrayList[String]()
    val chunkWorld = shell(world, false)
    val properties: Array[Byte] = SlimeSerializer.serialize(chunkWorld, new Array[Byte](4))
    val jobs: List[java.util.concurrent.Callable[Map[String, Array[Byte]]]] = new ArrayList[java.util.concurrent.Callable[Map[String, Array[Byte]]]]()
    // Compare complete chunk content, not only vanilla's unsaved flag: PDC, entity movement,
    // lighting and pending ticks can change without that flag changing between frames.
    val storage = world.getChunkStorage().iterator()
    while (storage.hasNext()) {
      val source = storage.next()
      val coordinate: String = s"${source.getX()}_${source.getZ()}"; coordinates.add(coordinate)
      var chunk: SlimeChunk = source
      source match {
        case live: NMSSlimeChunk =>
          chunk = PartiallySerializedSlimeChunk.of(live,
            metadata.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS).booleanValue(),
            metadata.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS).booleanValue(),
            metadata.getPropertyMap().getValue(SlimeProperties.SAVE_POI).booleanValue())
        case _ =>
      }
      val frozen: SlimeChunk = chunk
      jobs.add(() => {
        val changed: Map[String, Array[Byte]] = new HashMap[String, Array[Byte]]()
        // Serializer updates its world metadata map, so each job owns its shell.
        val localWorld = shell(chunkWorld, false)
        val raw: Array[Byte] = SlimeSerializer.captureChunks(localWorld, List.of[SlimeChunk](frozen))
        // Include format properties in the fingerprint; their changes affect the SRF header.
        val digest = MessageDigest.getInstance("SHA-256"); digest.update(properties); digest.update(raw)
        val chunkKey = root + "/" + coordinate
        val fingerprint: Array[Byte] = digest.digest(); val old: Cached = captured.get(chunkKey)
        if (old != null && MessageDigest.isEqual(old.digest, fingerprint)) changed
        else {
          put(chunkKey, SlimeSerializer.serialize(localWorld, raw), blobs, previous, changed, old == null)
          captured.put(chunkKey, new Cached(fingerprint))
          changed
        }
      })
    }
    val carriedRoot = root
    val carriedIt = carried.iterator()
    while (carriedIt.hasNext()) {
      val coordinate = carriedIt.next()
      // Only chunks with a committed snapshot may be carried; otherwise fail so the next capture is complete.
      val manifest: Array[Byte] = previous.get(carriedRoot + "/" + coordinate + "/manifest")
      if (manifest == null || manifest.length == 0) throw new IllegalStateException("Carried chunk has no committed snapshot: " + coordinate)
      coordinates.add(coordinate)
    }
    if (progress != null) progress.phase("chunks:" + world.getName(), jobs.size())
    if (pipeline == null) {
      val it = jobs.iterator()
      while (it.hasNext()) updates.putAll(it.next().call())
    } else {
      val results = pipeline.parallel[Map[String, Array[Byte]]](jobs, () => { if (progress != null) progress.advance() }).iterator()
      while (results.hasNext()) updates.putAll(results.next())
    }
    Collections.sort(coordinates)
    updates.put(root + "/index", ("1\n" + String.join("\n", coordinates)).getBytes(StandardCharsets.UTF_8))
    val present: Set[String] = new HashSet[String](coordinates)
    val stale = index(previous.get(root + "/index")).iterator()
    while (stale.hasNext()) {
      val coordinate = stale.next()
      if (!present.contains(coordinate)) {
        clear(root + "/" + coordinate, previous, updates); captured.remove(root + "/" + coordinate)
      }
    }
    // The first incremental checkpoint atomically supersedes legacy SRF parts.
    clear(legacyKey, previous, updates)
  }
}

object LinkChunkSnapshots {
  private final case class Cached(digest: Array[Byte])

  /** Main-thread capture only. Workers must never traverse live worlds or Bukkit objects. */
  private[server] def freeze(live: SlimeWorld): SlimeWorld = {
    val metadata = shell(live, true)
    val chunks = new Long2ObjectOpenHashMap[SlimeChunk]()
    val it = live.getChunkStorage().iterator()
    while (it.hasNext()) {
      val source = it.next()
      val copy: SlimeChunk = source match {
        case nms: NMSSlimeChunk =>
          PartiallySerializedSlimeChunk.of(nms,
            metadata.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS).booleanValue(),
            metadata.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS).booleanValue(),
            metadata.getPropertyMap().getValue(SlimeProperties.SAVE_POI).booleanValue())
        case _ =>
          val sections = source.getSections(); val cloned = new Array[SlimeChunkSection](sections.length)
          var i = 0
          while (i < sections.length) {
            val section = sections(i)
            cloned(i) = new com.infernalsuite.asp.skeleton.SlimeChunkSectionSkeleton(section.getBlockStatesTag(), section.getBiomeTag(),
              if (section.getBlockLight() == null) null else section.getBlockLight().clone(), if (section.getSkyLight() == null) null else section.getSkyLight().clone())
            i += 1
          }
          new com.infernalsuite.asp.skeleton.SlimeChunkSkeleton(source.getX(), source.getZ(), cloned, source.getHeightMaps(),
            List.copyOf(source.getTileEntities()), List.copyOf(source.getEntities()), Map.copyOf(source.getExtraData()), source.getUpgradeData(),
            source.getPoiChunkSections(), source.getBlockTicks(), source.getFluidTicks())
      }
      chunks.put(Util.chunkPosition(copy.getX(), copy.getZ()), copy)
    }
    new SkeletonSlimeWorld(metadata.getName(), null, true, chunks, metadata.getExtraData(), metadata.getPropertyMap(), metadata.getDataVersion())
  }

  /** What the last capture of one chunk saw. Only touched on the server thread. */
  private final case class Seen(identity: AnyRef, modCount: Long)
  private final class WorldState {
    val seen: Map[java.lang.Long, Seen] = new HashMap[java.lang.Long, Seen]()
    /** Last full copy per chunk; its sections/light/heightmaps are immutable and reused while blocks are unchanged. */
    val lastCopy: Map[java.lang.Long, PartiallySerializedSlimeChunk] = new HashMap[java.lang.Long, PartiallySerializedSlimeChunk]()
    /** Chunks (with neighbours) modified in the previous round: re-copied once more because light settles a tick later. */
    var settling: Set[java.lang.Long] = Set.of[java.lang.Long]()
    var sweepCursor: Long = 0L
  }
  /** Every chunk is content-checked at least once per this many checkpoints, even if no change signal fired. */
  private val SWEEP_ROUNDS: Int = 20
  /** Changed chunks copied on the server thread, plus coordinates whose committed snapshot is still current. */
  private[server] final case class Frozen(changed: SlimeWorld, carried: Set[String])

  /** State that can change without ChunkAccess.markUnsaved: always re-captured (content hashing drops no-ops). */
  private def volatileState(chunk: net.minecraft.world.level.chunk.LevelChunk): Boolean = {
    if (!chunk.blockEntities.isEmpty() || !chunk.persistentDataContainer.isEmpty()) return true
    chunk.getBlockTicks() match {
      case blocks: net.minecraft.world.ticks.LevelChunkTicks[?] if blocks.count() > 0 => return true
      case _ =>
    }
    chunk.getFluidTicks() match {
      case fluids: net.minecraft.world.ticks.LevelChunkTicks[?] if fluids.count() > 0 => return true
      case _ =>
    }
    val holder = com.infernalsuite.asp.util.NmsUtil.getChunkHolder(chunk)
    if (holder == null) return true // unknown: be conservative
    val entities = holder.getEntityChunk()
    if (entities != null && entities.entities.size() > 0) return true
    val poi = holder.getPoiChunk()
    poi != null && !poi.isEmpty() // villager job-site occupancy changes without a block change
  }

  private def copy(source: SlimeChunk, ticks: Boolean, fluids: Boolean, poi: Boolean): SlimeChunk = {
    source match {
      case nms: NMSSlimeChunk => return PartiallySerializedSlimeChunk.of(nms, ticks, fluids, poi)
      case _ =>
    }
    val sections = source.getSections(); val cloned = new Array[SlimeChunkSection](sections.length)
    var i = 0
    while (i < sections.length) {
      val section = sections(i)
      cloned(i) = new com.infernalsuite.asp.skeleton.SlimeChunkSectionSkeleton(section.getBlockStatesTag(), section.getBiomeTag(),
        if (section.getBlockLight() == null) null else section.getBlockLight().clone(), if (section.getSkyLight() == null) null else section.getSkyLight().clone())
      i += 1
    }
    new com.infernalsuite.asp.skeleton.SlimeChunkSkeleton(source.getX(), source.getZ(), cloned, source.getHeightMaps(),
      List.copyOf(source.getTileEntities()), List.copyOf(source.getEntities()), Map.copyOf(source.getExtraData()), source.getUpgradeData(),
      source.getPoiChunkSections(), source.getBlockTicks(), source.getFluidTicks())
  }

  @throws[Exception]
  private[server] def key(worldKey: String): String = {
    // Keep aggregate IDs bounded even for long world names.
    val end = worldKey.indexOf("/world/")
    worldKey.substring(0, end) + "/chunks/" + HexFormat.of().formatHex(hash(worldKey.getBytes(StandardCharsets.UTF_8)))
  }
  @throws[Exception]
  private def hash(bytes: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(bytes)
  private def shell(world: SlimeWorld, metadata: Boolean): SkeletonSlimeWorld = {
    var properties = new SlimePropertyMap(new HashMap[String, BinaryTag](world.getPropertyMap().getProperties()))
    if (!metadata) {
      properties = new SlimePropertyMap()
      properties.setValue(SlimeProperties.SAVE_BLOCK_TICKS, world.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS))
      properties.setValue(SlimeProperties.SAVE_FLUID_TICKS, world.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS))
      properties.setValue(SlimeProperties.SAVE_POI, world.getPropertyMap().getValue(SlimeProperties.SAVE_POI))
    }
    val extra = new ConcurrentHashMap[String, BinaryTag]()
    if (metadata) {
      extra.putAll(world.getExtraData())
      world match {
        case live: SlimeInMemoryWorld =>
          extra.put("BukkitValues", Converter.convertTag[BinaryTag](live.getInstance().persistentDataContainer.toTagCompound().copy()))
        case _ =>
      }
    }
    new SkeletonSlimeWorld(world.getName(), null, true, new Long2ObjectOpenHashMap[SlimeChunk](), extra, properties, world.getDataVersion())
  }

  private def clear(key: String, previous: Map[String, Array[Byte]], updates: Map[String, Array[Byte]]): Unit = {
    val it = previous.entrySet().iterator()
    while (it.hasNext()) {
      val entry = it.next()
      if (entry.getKey().startsWith(key + "/") && entry.getValue().length > 0)
        updates.put(entry.getKey(), new Array[Byte](0))
    }
  }
  @throws[Exception]
  private def put(key: String, raw: Array[Byte], blobs: WorldBlobs, previous: Map[String, Array[Byte]], updates: Map[String, Array[Byte]], comparePersisted: Boolean): Unit = {
    // On restart, compare against the persisted object before allocating another S3 revision.
    if (comparePersisted) {
      val old = BlobSnapshots.get(previous, key)
      if (old.isPresent() && blobs.matches(old.get(), raw)) return
    }
    val parts: Map[String, Array[Byte]] = new HashMap[String, Array[Byte]](); BlobSnapshots.put(parts, key, blobs.encode(key, raw))
    val it = previous.entrySet().iterator()
    while (it.hasNext()) {
      val entry = it.next()
      if (entry.getKey().startsWith(key + "/") && !parts.containsKey(entry.getKey()) && entry.getValue().length > 0)
        updates.put(entry.getKey(), new Array[Byte](0))
    }
    updates.putAll(parts)
  }
  private def index(bytes: Array[Byte]): List[String] = {
    if (bytes == null) return List.of[String]()
    val value = new String(bytes, StandardCharsets.UTF_8)
    if (!value.startsWith("1\n")) throw new IllegalStateException("Unknown chunk snapshot format")
    if (value.length() == 2) return List.of[String]()
    val result: List[String] = List.of[String](value.substring(2).split("\n", -1)*)
    if (new HashSet[String](result).size() != result.size() || result.stream().anyMatch((s: String) => !s.matches("-?[0-9]+_-?[0-9]+")))
      throw new IllegalStateException("Invalid chunk snapshot index")
    result
  }
  @throws[Exception]
  private[server] def restore(name: String, legacyKey: String, blobs: WorldBlobs, states: Map[String, Array[Byte]]): Optional[Array[Byte]] = {
    val root = key(legacyKey)
    if (!states.containsKey(root + "/index")) {
      val legacy = BlobSnapshots.get(states, legacyKey)
      return if (legacy.isEmpty()) Optional.empty[Array[Byte]]() else Optional.of(blobs.decode(legacy.get()))
    }
    // Data fixers run once when the assembled SRF is loaded by the normal world driver.
    val meta = SlimeWorldReaderRegistry.readWorld(null, name, read(root + "/meta", blobs, states), new SlimePropertyMap(), true)
    val chunks = new Long2ObjectOpenHashMap[SlimeChunk]()
    val it = index(states.get(root + "/index")).iterator()
    while (it.hasNext()) {
      val coordinate = it.next()
      val part = SlimeWorldReaderRegistry.readWorld(null, name, read(root + "/" + coordinate, blobs, states), new SlimePropertyMap(), true)
      if (part.getChunkStorage().size() != 1) throw new IllegalStateException("Invalid chunk checkpoint")
      val chunk: SlimeChunk = part.getChunkStorage().iterator().next()
      if (!coordinate.equals(s"${chunk.getX()}_${chunk.getZ()}")) throw new IllegalStateException("Chunk coordinate mismatch")
      chunks.put(Util.chunkPosition(chunk.getX(), chunk.getZ()), chunk)
    }
    val world = new SkeletonSlimeWorld(name, null, true, chunks, meta.getExtraData(), meta.getPropertyMap(), meta.getDataVersion())
    Optional.of(SlimeSerializer.serialize(world, SlimeSerializer.captureChunks(world, world.getChunkStorage())))
  }
  @throws[Exception]
  private def read(key: String, blobs: WorldBlobs, states: Map[String, Array[Byte]]): Array[Byte] =
    blobs.decode(BlobSnapshots.get(states, key).orElseThrow(() => new IllegalStateException("Missing chunk snapshot: " + key)))
}
