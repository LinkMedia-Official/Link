package io.linkmc.core

import io.linkmc.api.LinkApi.{Destination, Reservation, Session}
import io.linkmc.core.Database.{lock, sharedLock, sql, update}

import java.sql.Connection
import java.util.{Objects, UUID}
import scala.util.Using

/** Authenticated proxy -> backend handoff via SQL, never client plugin messages. */
final class ArrivalStore(db: Database) {
  private val sessions = new SessionStore(db, 30)
  private val network = new NetworkStore(db)

  @throws[Exception]
  def issue(ticket: UUID, reservation: Reservation, transfer: UUID): Unit =
    db.transaction[AnyRef] { c => issue(c, ticket, reservation, transfer); null }

  @throws[Exception]
  private[core] def issue(c: Connection, ticket: UUID, reservation: Reservation, transfer: UUID): Unit = {
    lock(c, "session:" + reservation.player()); lock(c, "allocation")
    update(c, "UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED' AND expires_at<=clock_timestamp()", reservation.player())
    val existing = Using.resource(sql(c, "SELECT * FROM link_arrivals WHERE id=?", ticket)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next()) {
          if (!r.getObject("player", classOf[UUID]).equals(reservation.player()) || !r.getObject("reservation", classOf[UUID]).equals(reservation.id()) || !Objects.equals(r.getObject("transfer", classOf[UUID]), transfer))
            throw new ConflictException("Arrival ID reused")
          if (!r.getString("state").equals("ISSUED")) throw new ConflictException("Arrival no longer pending")
          true
        } else false
      }
    }
    if (existing) return
    Using.resource(sql(c, "SELECT c.node FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE r.id=? AND r.player=? AND r.state='RESERVED' AND r.expires_at>clock_timestamp() AND c.ready", reservation.id(), reservation.player())) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (!r.next() || !r.getString(1).equals(reservation.destination().node())) throw new ConflictException("Invalid arrival reservation")
      }
    }
    update(c, "INSERT INTO link_arrivals(id,player,reservation,transfer,node,state,expires_at) SELECT ?,?,?,?,?,'ISSUED',expires_at FROM link_reservations WHERE id=?",
      ticket, reservation.player(), reservation.id(), transfer, reservation.destination().node(), reservation.id())
  }

  /** Single-use claim. The backend caches the returned result for its one connection. */
  @throws[Exception]
  def claim(player: UUID, node: String): ArrivalStore.Arrival =
    db.transaction[ArrivalStore.Arrival] { c =>
      sharedLock(c, "node-startup:" + node)
      lock(c, "session:" + player)
      Using.resource(sql(c, "SELECT * FROM link_arrivals WHERE player=? AND state='ISSUED' AND expires_at>clock_timestamp() FOR UPDATE", player)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (!r.next() || !r.getString("node").equals(node)) throw new ConflictException("No valid arrival for this backend")
          val transfer = r.getObject("transfer", classOf[UUID])
          val ticket = r.getObject("id", classOf[UUID])
          val owner: Session = if (transfer == null) sessions.acquire(c, player, node) else sessions.accept(c, player, transfer, node)
          val destination: Destination = network.admit(c, r.getObject("reservation", classOf[UUID]), owner)
          update(c, "UPDATE link_arrivals SET state='CLAIMED',epoch=? WHERE id=?", owner.epoch(), ticket)
          ArrivalStore.Arrival(ticket, owner, destination)
        }
      }
    }

  @throws[Exception]
  def cancel(ticket: UUID, player: UUID): Unit =
    db.transaction[AnyRef] { c =>
      lock(c, "session:" + player); lock(c, "allocation")
      Using.resource(sql(c, "SELECT reservation FROM link_arrivals WHERE id=? AND player=? AND state='ISSUED' FOR UPDATE", ticket, player)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) {
            update(c, "UPDATE link_arrivals SET state='CANCELLED' WHERE id=?", ticket)
            update(c, "DELETE FROM link_reservations WHERE id=? AND state='RESERVED'", r.getObject(1, classOf[UUID]))
          }
        }
      }
      null
    }
}

object ArrivalStore {
  final case class Arrival(ticket: UUID, session: Session, destination: Destination)
}
