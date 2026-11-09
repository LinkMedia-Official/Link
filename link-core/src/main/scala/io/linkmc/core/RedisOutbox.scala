package io.linkmc.core

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.linkmc.core.Database.{sql, update}

import java.util.{Map, UUID}
import scala.util.Using

/**
 * At-least-once invalidation hints. SQL remains authoritative. Consumers reread
 * aggregate versions from SQL and must never blindly replay Redis notifications.
 * A crash after XADD and before marking delivered intentionally causes a duplicate.
 */
final class RedisOutbox(db: Database, uri: String) extends AutoCloseable {
  private val client: RedisClient = RedisClient.create(uri)
  client.setDefaultTimeout(java.time.Duration.ofSeconds(3))
  private val connection: StatefulRedisConnection[String, String] =
    try client.connect()
    catch { case e: RuntimeException => client.shutdown(); throw e }

  @throws[Exception]
  def flush(limit: Int): Int = {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid batch size")
    db.transaction[java.lang.Integer] { c =>
      var count = 0
      Using.resource(sql(c, "SELECT id FROM link_outbox WHERE NOT delivered ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED", Int.box(limit))) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) {
            val id = r.getObject(1, classOf[UUID])
            connection.sync().xadd("link:commits", Map.of[String, String]("operation", id.toString))
            update(c, "UPDATE link_outbox SET delivered=true WHERE id=?", id); count += 1
          }
        }
      }
      Int.box(count)
    }
  }

  override def close(): Unit = { connection.close(); client.shutdown() }
}
