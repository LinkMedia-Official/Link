package io.linkmc.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.{Arrays, Optional}

/** Splits large native world snapshots into bounded aggregates in the SAME SQL transaction. */
final class BlobSnapshots private ()

object BlobSnapshots {
  private final val PART = 8 * 1024 * 1024

  @throws[Exception]
  def put(states: java.util.Map[String, Array[Byte]], key: String, bytes: Array[Byte]): Unit = {
    if (bytes.length > 512 * 1024 * 1024) throw new IllegalArgumentException("Snapshot exceeds 512 MiB")
    val parts = (bytes.length + PART - 1) / PART
    val manifest = ByteBuffer.allocate(40).putInt(bytes.length).putInt(parts).put(MessageDigest.getInstance("SHA-256").digest(bytes))
    states.put(key + "/manifest", manifest.array())
    var i = 0
    while (i < parts) { states.put(key + "/" + i, Arrays.copyOfRange(bytes, i * PART, Math.min(bytes.length, (i + 1) * PART))); i += 1 }
  }

  @throws[Exception]
  def get(states: java.util.Map[String, Array[Byte]], key: String): Optional[Array[Byte]] = {
    val data = states.get(key + "/manifest"); if (data == null || data.length == 0) return Optional.empty[Array[Byte]]()
    if (data.length != 40) throw new IllegalStateException("Invalid snapshot manifest")
    val manifest = ByteBuffer.wrap(data); val length = manifest.getInt(); val parts = manifest.getInt()
    if (length < 0 || length > 512 * 1024 * 1024 || parts != (length + PART - 1) / PART) throw new IllegalStateException("Invalid snapshot size")
    val digest = new Array[Byte](32); manifest.get(digest); val result = new Array[Byte](length)
    var i = 0
    while (i < parts) {
      val part = states.get(key + "/" + i); val expected = Math.min(PART, length - i * PART)
      if (part == null || part.length != expected) throw new IllegalStateException("Missing snapshot part")
      System.arraycopy(part, 0, result, i * PART, part.length)
      i += 1
    }
    if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(result))) throw new IllegalStateException("Snapshot checksum mismatch")
    Optional.of(result)
  }
}
