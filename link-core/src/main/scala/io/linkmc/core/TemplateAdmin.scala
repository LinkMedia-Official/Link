package io.linkmc.core

import java.nio.file.{Files, Path}
import scala.util.Using

/** Offline SRF publication; no server/world files are modified. */
final class TemplateAdmin private ()

object TemplateAdmin {
  @throws[Exception]
  def main(args: Array[String]): Unit = {
    if (args.length != 3) throw new IllegalArgumentException("Usage: <template> <version> <file.slime>")
    val url = required("LINK_JDBC_URL"); val user = required("LINK_DB_USER"); val password = required("LINK_DB_PASSWORD")
    Using.resource(Database.pooled(url, user, password)) { db =>
      val storage = System.getenv().getOrDefault("LINK_WORLD_STORAGE", "database")
      if (storage.equals("slimevault")) db.worldStorage(new SlimeVaultStore(required("LINK_S3_ENDPOINT"),
        System.getenv().getOrDefault("LINK_S3_REGION", "slimevault"), required("LINK_S3_BUCKET"),
        System.getenv().getOrDefault("LINK_S3_PREFIX", "link"), required("LINK_S3_ACCESS_KEY"), required("LINK_S3_SECRET_KEY")))
      else if (!storage.equals("database")) throw new IllegalArgumentException("Invalid LINK_WORLD_STORAGE")
      db.migrate()
      new TemplateStore(db).publish(args(0), Integer.parseInt(args(1)), Files.readAllBytes(Path.of(args(2))))
    }
    System.out.println("Published immutable template " + args(0) + ":" + args(1))
  }

  private def required(name: String): String = {
    val value = System.getenv(name)
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name)
    value
  }
}
