package io.linkmc.core

import io.linkmc.core.Database.{instant, sql, update}

import java.sql.Timestamp
import java.time.Duration
import java.util.{ArrayList, List, UUID}
import scala.util.Using

/** Bounded retention of historical events; current state and idempotency receipts are never deleted. */
final class EventRetention(db: Database) {
  @throws[Exception]
  def prune(hours: Int, batch: Int): EventRetention.Result = {
    if (hours < 1 || hours > 87600 || batch < 1 || batch > 1000) throw new IllegalArgumentException("Invalid retention limits")
    db.transaction[EventRetention.Result] { c =>
      val cutoff: Timestamp = Using.resource(sql(c, "SELECT clock_timestamp()")) { s =>
        Using.resource(s.executeQuery()) { r =>
          r.next(); Timestamp.from(instant(r, 1).minus(Duration.ofHours(hours)))
        }
      }
      val operations: List[UUID] = new ArrayList[UUID]()
      // Recovery reads link_state, so even the last legacy event can expire.
      // New operations are created with events_pruned=true and need no scan/update here.
      Using.resource(sql(c, "SELECT o.id FROM link_operations o WHERE NOT o.events_pruned AND o.created_at<? ORDER BY o.created_at,o.id LIMIT ? FOR UPDATE SKIP LOCKED", cutoff, Int.box(batch))) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) operations.add(r.getObject(1, classOf[UUID]))
        }
      }
      var events = 0
      val ops = operations.iterator()
      while (ops.hasNext) {
        val operation = ops.next()
        events += update(c, "DELETE FROM link_events WHERE operation=?", operation)
        update(c, "UPDATE link_operations SET events_pruned=true WHERE id=?", operation)
      }
      val delivered: List[UUID] = new ArrayList[UUID]()
      Using.resource(sql(c, "SELECT b.id FROM link_outbox b JOIN link_operations o ON o.id=b.id WHERE b.delivered AND o.created_at<? ORDER BY o.created_at,o.id LIMIT ? FOR UPDATE SKIP LOCKED", cutoff, Int.box(batch))) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) delivered.add(r.getObject(1, classOf[UUID]))
        }
      }
      val del = delivered.iterator()
      while (del.hasNext) update(c, "DELETE FROM link_outbox WHERE id=? AND delivered", del.next())
      val requests: List[UUID] = new ArrayList[UUID]()
      Using.resource(sql(c, "SELECT id FROM link_teleport_requests WHERE expires_at<? ORDER BY expires_at,id LIMIT ? FOR UPDATE SKIP LOCKED", cutoff, Int.box(batch))) { s =>
        Using.resource(s.executeQuery()) { r => while (r.next()) requests.add(r.getObject(1, classOf[UUID])) }
      }
      val req = requests.iterator()
      while (req.hasNext) update(c, "DELETE FROM link_teleport_requests WHERE id=?", req.next())
      val groupRequests: List[UUID] = new ArrayList[UUID]()
      Using.resource(sql(c, "SELECT id FROM link_group_requests WHERE expires_at<? ORDER BY expires_at,id LIMIT ? FOR UPDATE SKIP LOCKED", cutoff, Int.box(batch))) { s =>
        Using.resource(s.executeQuery()) { r => while (r.next()) groupRequests.add(r.getObject(1, classOf[UUID])) }
      }
      val greq = groupRequests.iterator()
      while (greq.hasNext) update(c, "DELETE FROM link_group_requests WHERE id=?", greq.next())
      requests.addAll(groupRequests)
      EventRetention.Result(operations.size(), events, delivered.size(), requests.size())
    }
  }
}

object EventRetention {
  final case class Result(operations: Int, events: Int, notifications: Int, requests: Int)
}
