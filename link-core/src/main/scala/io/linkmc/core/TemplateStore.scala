package io.linkmc.core

import io.linkmc.api.LinkApi
import io.linkmc.core.Database.{lock, sql, update}

import java.security.MessageDigest
import java.util.{ArrayList, List, Optional}
import scala.util.Using

/** Immutable published SRF blobs. Editing always happens in a separate world. */
final class TemplateStore(db: Database) {
  @throws[Exception]
  def publish(name: String, version: Int, srf: Array[Byte]): Unit = publish(name, version, srf, LinkApi.ChannelPolicy.defaults())
  @throws[Exception]
  def publish(name: String, version: Int, srf: Array[Byte], policy: LinkApi.ChannelPolicy): Unit = {
    LinkApi.requireName(name)
    if (version < 1 || srf.length == 0) throw new IllegalArgumentException("Invalid template")
    val bytes = srf.clone(); val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val stored = db.worldBlobs().encode("template:" + name + ":" + version, bytes)
    db.transaction[AnyRef] { c =>
      lock(c, "template:" + name + ":" + version)
      val published = Using.resource(sql(c, "SELECT sha256 FROM link_templates WHERE name=? AND version=?", name, Int.box(version))) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) {
            if (!MessageDigest.isEqual(digest, r.getBytes(1))) throw new ConflictException("Published template versions are immutable")
            if (!policy.equals(TemplateStore.policy(c, name, version))) throw new ConflictException("Published channel policy is immutable")
            true
          } else false
        }
      }
      if (!published) {
        update(c, "INSERT INTO link_templates(name,version,payload,sha256) VALUES (?,?,?,?)", name, Int.box(version), stored, digest)
        update(c, "INSERT INTO link_template_channels(name,version,capacity,max_channels,auto_expand,network_wide) VALUES (?,?,?,?,?,?)", name, Int.box(version), Int.box(policy.capacity()), Int.box(policy.maxChannels()), Boolean.box(policy.autoExpand()), Boolean.box(policy.networkWide()))
      }
      null
    }
    ()
  }
  @throws[Exception]
  def read(name: String, version: Int): Array[Byte] =
    find(name, version).orElseThrow[ConflictException](() => new ConflictException("Unknown template version"))
  /** Only absence returns empty; database and checksum failures must still stop startup. */
  @throws[Exception]
  def find(name: String, version: Int): Optional[Array[Byte]] = {
    LinkApi.requireName(name)
    db.transaction[Optional[Array[Byte]]] { c =>
      Using.resource(sql(c, "SELECT payload,sha256 FROM link_templates WHERE name=? AND version=?", name, Int.box(version))) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (!r.next()) Optional.empty[Array[Byte]]()
          else {
            val bytes = db.worldBlobs().decode(r.getBytes(1))
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), r.getBytes(2))) throw new ConflictException("Template checksum mismatch")
            Optional.of(bytes)
          }
        }
      }
    }
  }
  @throws[Exception]
  def list(): List[String] =
    db.transaction[List[String]] { c =>
      val result = new ArrayList[String]()
      Using.resource(sql(c, "SELECT name,version FROM link_templates ORDER BY name,version")) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) result.add(r.getString(1) + ":" + r.getInt(2))
        }
      }
      List.copyOf(result)
    }
}

object TemplateStore {
  @throws[Exception]
  private[core] def policy(c: java.sql.Connection, name: String, version: Int): LinkApi.ChannelPolicy =
    Using.resource(sql(c, "SELECT * FROM link_template_channels WHERE name=? AND version=?", name, Int.box(version))) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next()) new LinkApi.ChannelPolicy(r.getInt("capacity"), r.getInt("max_channels"), r.getBoolean("auto_expand"), r.getBoolean("network_wide")) else LinkApi.ChannelPolicy.defaults()
      }
    }
}
