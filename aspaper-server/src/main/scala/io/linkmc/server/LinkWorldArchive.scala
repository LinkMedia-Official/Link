package io.linkmc.server

import com.infernalsuite.asp.serialization.slime.reader.SlimeWorldReaderRegistry
import com.infernalsuite.asp.api.world.SlimeChunk
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap
import com.infernalsuite.asp.serialization.slime.SlimeSerializer
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld
import com.infernalsuite.asp.util.Util
import io.linkmc.core.{BlobSnapshots, WorldBlobs}
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.{HashSet, HexFormat, List, Map, Optional}

/**
 * Read-only access to worlds that older Link builds checkpointed into the database (per-chunk layout or a legacy
 * whole-world SRF). Only used to export them once to vanilla region files.
 */
object LinkWorldArchive {
  @throws[Exception]
  private[server] def key(worldKey: String): String = {
    // Keep aggregate IDs bounded even for long world names.
    val end = worldKey.indexOf("/world/")
    worldKey.substring(0, end) + "/chunks/" + HexFormat.of().formatHex(hash(worldKey.getBytes(StandardCharsets.UTF_8)))
  }
  @throws[Exception]
  private def hash(bytes: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(bytes)
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
