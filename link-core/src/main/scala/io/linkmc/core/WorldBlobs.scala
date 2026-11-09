package io.linkmc.core

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream, IOException}
import java.security.MessageDigest
import java.util.Arrays
import scala.util.Using

/** SQL stores an immutable S3 revision reference only after upload confirmation. */
final class WorldBlobs(store: WorldBlobs.Store) {
  private val recent: java.util.Map[String, WorldBlobs.Cached] = new java.util.concurrent.ConcurrentHashMap[String, WorldBlobs.Cached]()
  private val locks: java.util.Map[String, AnyRef] = new java.util.concurrent.ConcurrentHashMap[String, AnyRef]()

  @throws[Exception]
  def encode(identity: String, world: Array[Byte]): Array[Byte] = {
    if (store == null) return world
    locks.computeIfAbsent(identity, (key: String) => new Object()).synchronized {
      val digest = MessageDigest.getInstance("SHA-256").digest(world)
      val cached = recent.get(identity)
      if (cached != null && MessageDigest.isEqual(digest, cached.digest)) cached.reference.clone()
      else {
        val revision = store.put(world)
        val bytes = new ByteArrayOutputStream()
        Using.resource(new DataOutputStream(bytes)) { out =>
          out.write(WorldBlobs.MAGIC); out.writeUTF(revision.bucket); out.writeUTF(revision.key); out.writeUTF(revision.version); out.write(digest)
        }
        val reference = bytes.toByteArray(); recent.put(identity, WorldBlobs.Cached(digest, reference)); reference.clone()
      }
    }
  }
  /** Compare the checksum embedded in a reference; do not download an already restored chunk again. */
  @throws[Exception]
  def matches(value: Array[Byte], world: Array[Byte]): Boolean = {
    if (value.length < WorldBlobs.MAGIC.length || !Arrays.equals(Arrays.copyOf(value, WorldBlobs.MAGIC.length), WorldBlobs.MAGIC)) return Arrays.equals(value, world)
    if (store == null) throw new IllegalStateException("World references SlimeVault; enable the matching storage configuration")
    Using.resource(new DataInputStream(new ByteArrayInputStream(value))) { input =>
      input.skipNBytes(WorldBlobs.MAGIC.length); input.readUTF(); input.readUTF(); input.readUTF(); val digest = input.readNBytes(32)
      if (digest.length != 32 || input.read() != -1) throw new IOException("Invalid world revision reference")
      MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(world))
    }
  }
  @throws[Exception]
  def decode(value: Array[Byte]): Array[Byte] = {
    if (value.length < WorldBlobs.MAGIC.length || !Arrays.equals(Arrays.copyOf(value, WorldBlobs.MAGIC.length), WorldBlobs.MAGIC)) return value
    if (store == null) throw new IllegalStateException("World references SlimeVault; enable the matching storage configuration")
    Using.resource(new DataInputStream(new ByteArrayInputStream(value))) { input =>
      input.skipNBytes(WorldBlobs.MAGIC.length)
      val revision = new WorldBlobs.Revision(input.readUTF(), input.readUTF(), input.readUTF())
      val digest = input.readNBytes(32)
      if (digest.length != 32 || input.read() != -1) throw new IOException("Invalid world revision reference")
      val world = store.get(revision)
      if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(world))) throw new IOException("SlimeVault world checksum mismatch")
      world
    }
  }
}

object WorldBlobs {
  private val MAGIC: Array[Byte] = "LINK-S3-REVISION-1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
  final case class Revision(bucket: String, key: String, version: String)
  trait Store {
    @throws[Exception]
    def put(world: Array[Byte]): Revision
    @throws[Exception]
    def get(revision: Revision): Array[Byte]
  }
  private final case class Cached(digest: Array[Byte], reference: Array[Byte])
}
