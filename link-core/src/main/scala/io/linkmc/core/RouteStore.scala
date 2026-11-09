package io.linkmc.core

import io.linkmc.api.LinkApi.{Destination, Reservation, Session}
import io.linkmc.core.Database.{instant, lock, sql, update}

import java.util.{ArrayList, Collection, Collections, Optional, UUID}
import scala.util.Using

/** Backend commands or plugins request travel; only the source backend may freeze/checkpoint its player. */
final class RouteStore(db: Database) {
  import RouteStore.{Receipt, Request}

  @throws[Exception]
  def requestTravel(player: UUID, id: UUID, destination: Destination): Reservation =
    db.transaction[Reservation] { c =>
      lock(c, "session:" + player)
      val prior = RouteStore.receipt(c, id)
      if (prior.isPresent()) {
        val reservation = prior.get().request.reservation
        if (!reservation.player().equals(player) || (destination != null && !destination.equals(reservation.destination()))) throw new ConflictException("Route ID reused")
        reservation
      } else {
        val reservation = new NetworkStore(db).reserve(c, player, id, destination, null)
        request(c, id, reservation); reservation
      }
    }

  @throws[Exception]
  def request(id: UUID, reservation: Reservation): Unit =
    db.transaction[AnyRef] { c => request(c, id, reservation); null }

  @throws[Exception]
  private def request(c: java.sql.Connection, id: UUID, reservation: Reservation): Unit = {
    lock(c, "session:" + reservation.player())
    val prior = RouteStore.receipt(c, id)
    if (prior.isPresent()) {
      if (!prior.get().request.reservation.equals(reservation)) throw new ConflictException("Route ID reused")
      return
    }
    val owner: Session = SessionStore.get(c, reservation.player()); SessionStore.requireActive(c, owner)
    Using.resource(sql(c, "SELECT player,reservation FROM link_routes WHERE id=?", id)) { q =>
      Using.resource(q.executeQuery()) { r =>
        if (r.next()) {
          if (!r.getObject(1, classOf[UUID]).equals(reservation.player()) || !r.getObject(2, classOf[UUID]).equals(reservation.id())) throw new ConflictException("Route ID reused")
        } else update(c, "INSERT INTO link_routes(id,player,reservation,state) VALUES (?,?,?,'REQUESTED')", id, reservation.player(), reservation.id())
      }
    }
    update(c, "INSERT INTO link_route_receipts(id,player,node,epoch,details) VALUES (?,?,?,?,?)", id, owner.player(), owner.node(), java.lang.Long.valueOf(owner.epoch()), RouteStore.encode(reservation))
  }

  /** Durable claim survives polling, callbacks and a changed destination session epoch. */
  @throws[Exception]
  def claim(request: Request, owner: Session): Boolean =
    db.transaction[java.lang.Boolean] { c =>
      lock(c, "session:" + owner.player())
      if (!RouteStore.status(c, request.id).equals("REQUESTED")) java.lang.Boolean.FALSE
      else {
        var prior = RouteStore.receipt(c, request.id)
        if (prior.isEmpty()) { this.request(c, request.id, request.reservation); prior = RouteStore.receipt(c, request.id) }
        val saved: Receipt = prior.orElseThrow()
        if (!saved.request.equals(request)) throw new ConflictException("Route payload changed")
        if (saved.claimed) java.lang.Boolean.FALSE
        else {
          RouteStore.requireOwner(saved, owner); SessionStore.requireActive(c, owner)
          Using.resource(sql(c, "SELECT 1 FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE r.id=? AND r.player=? AND r.state='RESERVED' AND r.expires_at>clock_timestamp() AND c.ready AND c.node=?", request.reservation.id(), owner.player(), request.reservation.destination().node())) { q =>
            Using.resource(q.executeQuery()) { r =>
              if (!r.next()) throw new ConflictException("Travel reservation expired or destination unavailable")
            }
          }
          // Claimed work may wait for a large checkpoint. Crash/failed-route cleanup releases it.
          update(c, "UPDATE link_reservations SET expires_at='infinity' WHERE id=?", request.reservation.id())
          update(c, "UPDATE link_route_receipts SET claimed=true WHERE id=?", request.id); java.lang.Boolean.TRUE
        }
      }
    }.booleanValue()

  @throws[Exception]
  def completeLocal(request: Request, owner: Session): Destination =
    db.transaction[Destination] { c =>
      lock(c, "session:" + owner.player()); val saved: Receipt = RouteStore.receipt(c, request.id).orElseThrow()
      RouteStore.requireOwner(saved, owner)
      if (!saved.request.equals(request) || !saved.claimed || !owner.node().equals(request.reservation.destination().node())) throw new ConflictException("Invalid local route")
      if (RouteStore.status(c, request.id).equals("COMPLETE")) request.reservation.destination()
      else {
        if (!RouteStore.status(c, request.id).equals("REQUESTED")) throw new ConflictException("Local route no longer pending")
        val result: Destination = new NetworkStore(db).admit(c, request.reservation.id(), owner)
        update(c, "UPDATE link_routes SET state='COMPLETE' WHERE id=?", request.id); result
      }
    }

  @throws[Exception]
  def prepareRemote(request: Request, owner: Session): Unit =
    db.transaction[AnyRef] { c =>
      lock(c, "session:" + owner.player()); val saved: Receipt = RouteStore.receipt(c, request.id).orElseThrow(); RouteStore.requireOwner(saved, owner)
      if (!saved.request.equals(request) || !saved.claimed || owner.node().equals(request.reservation.destination().node())) throw new ConflictException("Invalid remote route")
      if (RouteStore.status(c, request.id).equals("REQUESTED")) {
        new SessionStore(db, 30).prepare(c, owner, request.id, request.reservation.destination())
        new ArrivalStore(db).issue(c, request.id, request.reservation, request.id)
      }
      null
    }

  @throws[Exception]
  def reject(request: Request): Unit =
    db.transaction[AnyRef] { c =>
      lock(c, "session:" + request.reservation.player()); lock(c, "allocation")
      if (update(c, "UPDATE link_routes SET state='FAILED' WHERE id=? AND player=? AND state='REQUESTED'", request.id, request.reservation.player()) == 1)
        update(c, "DELETE FROM link_reservations WHERE id=? AND player=? AND state='RESERVED'", request.reservation.id(), request.reservation.player())
      null
    }

  @throws[Exception]
  def pending(source: String): java.util.List[Request] =
    db.transaction[java.util.List[Request]] { c =>
      val requests: java.util.List[Request] = new ArrayList[Request]()
      Using.resource(sql(c, "SELECT q.id,r.id AS reservation,r.player,r.channel,r.expires_at,c.node,c.world,r.x,r.y,r.z,r.yaw,r.pitch FROM link_routes q JOIN link_sessions s ON s.player=q.player JOIN link_reservations r ON r.id=q.reservation JOIN link_channels c ON c.id=r.channel WHERE q.state='REQUESTED' AND s.node=? AND s.state='ACTIVE' AND r.expires_at>clock_timestamp() ORDER BY q.created_at LIMIT 64", source)) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) requests.add(Request(r.getObject("id", classOf[UUID]), new Reservation(r.getObject("reservation", classOf[UUID]), r.getObject("player", classOf[UUID]), r.getString("channel"), new Destination(r.getString("node"), r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch")), instant(r, "expires_at"))))
        }
      }
      var i = 0
      while (i < requests.size()) { val saved = RouteStore.receipt(c, requests.get(i).id); if (saved.isPresent()) requests.set(i, saved.get().request); i += 1 }
      java.util.List.copyOf(requests)
    }

  @throws[Exception]
  def ready(id: UUID): Unit =
    db.transaction[AnyRef] { c =>
      val saved = RouteStore.receipt(c, id); if (saved.isPresent()) lock(c, "session:" + saved.get().owner.player()); lock(c, "allocation")
      if (update(c, "UPDATE link_routes SET state='READY' WHERE id=? AND state='REQUESTED'", id) == 1) {
        update(c, "UPDATE link_reservations SET expires_at=clock_timestamp()+interval '30 seconds' WHERE id=(SELECT reservation FROM link_routes WHERE id=?) AND state='RESERVED'", id)
        update(c, "UPDATE link_arrivals SET expires_at=clock_timestamp()+interval '30 seconds' WHERE id=? AND state='ISSUED'", id)
      }
      null
    }

  @throws[Exception]
  def readyFor(players: Collection[UUID]): java.util.List[Request] = {
    if (players.isEmpty()) return java.util.List.of[Request]()
    db.transaction[java.util.List[Request]] { c =>
      val result: java.util.List[Request] = new ArrayList[Request]()
      val placeholders = String.join(",", Collections.nCopies(players.size(), "?"))
      Using.resource(sql(c, "SELECT q.id,r.id AS reservation,r.player,r.channel,r.expires_at,c.node,c.world,r.x,r.y,r.z,r.yaw,r.pitch FROM link_routes q JOIN link_reservations r ON r.id=q.reservation JOIN link_channels c ON c.id=r.channel WHERE q.state='READY' AND q.player IN (" + placeholders + ") ORDER BY q.created_at LIMIT 64", players.toArray()*)) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) result.add(Request(r.getObject("id", classOf[UUID]), new Reservation(r.getObject("reservation", classOf[UUID]), r.getObject("player", classOf[UUID]), r.getString("channel"), new Destination(r.getString("node"), r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch")), instant(r, "expires_at"))))
        }
      }
      java.util.List.copyOf(result)
    }
  }

  @throws[Exception]
  def dispatch(id: UUID): Boolean =
    db.transaction[java.lang.Boolean] { c => java.lang.Boolean.valueOf(update(c, "UPDATE link_routes SET state='CONNECTING' WHERE id=? AND state='READY'", id) == 1) }.booleanValue()

  @throws[Exception]
  def finish(id: UUID, success: Boolean): Unit =
    db.transaction[AnyRef] { c => update(c, "UPDATE link_routes SET state=? WHERE id=? AND state IN ('REQUESTED','READY','CONNECTING')", if (success) "COMPLETE" else "FAILED", id); null }

  @throws[Exception]
  def status(id: UUID): String =
    db.transaction[String] { c =>
      Using.resource(sql(c, "SELECT state FROM link_routes WHERE id=?", id)) { s =>
        Using.resource(s.executeQuery()) { r => if (r.next()) r.getString(1) else "MISSING" }
      }
    }
}

object RouteStore {
  final case class Request(id: UUID, reservation: Reservation)

  private final case class Receipt(owner: Session, request: Request, claimed: Boolean)

  private def requireOwner(receipt: Receipt, owner: Session): Unit = {
    if (!receipt.owner.player().equals(owner.player()) || !receipt.owner.node().equals(owner.node()) || receipt.owner.epoch() != owner.epoch()) throw new ConflictException("Route belongs to an earlier player session")
  }

  @throws[Exception]
  private def status(c: java.sql.Connection, id: UUID): String =
    Using.resource(sql(c, "SELECT state FROM link_routes WHERE id=?", id)) { q =>
      Using.resource(q.executeQuery()) { r => if (r.next()) r.getString(1) else "MISSING" }
    }

  @throws[Exception]
  private def receipt(c: java.sql.Connection, id: UUID): Optional[Receipt] =
    Using.resource(sql(c, "SELECT * FROM link_route_receipts WHERE id=?", id)) { q =>
      Using.resource(q.executeQuery()) { r =>
        if (!r.next()) Optional.empty[Receipt]()
        else {
          val reservation = decode(r.getBytes("details"))
          Optional.of(Receipt(new Session(r.getObject("player", classOf[UUID]), r.getString("node"), r.getLong("epoch"), java.time.Instant.EPOCH), Request(id, reservation), r.getBoolean("claimed")))
        }
      }
    }

  @throws[Exception]
  private def encode(r: Reservation): Array[Byte] = {
    val bytes = new java.io.ByteArrayOutputStream()
    Using.resource(new java.io.DataOutputStream(bytes)) { out =>
      out.writeUTF(r.id().toString()); out.writeUTF(r.player().toString()); out.writeUTF(r.channel()); val d = r.destination()
      out.writeUTF(d.node()); out.writeUTF(d.world()); out.writeDouble(d.x()); out.writeDouble(d.y()); out.writeDouble(d.z()); out.writeFloat(d.yaw()); out.writeFloat(d.pitch())
      out.writeLong(r.expiresAt().getEpochSecond()); out.writeInt(r.expiresAt().getNano())
    }
    bytes.toByteArray()
  }

  @throws[Exception]
  private def decode(bytes: Array[Byte]): Reservation =
    Using.resource(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) { input =>
      val id = UUID.fromString(input.readUTF()); val player = UUID.fromString(input.readUTF()); val channel = input.readUTF()
      val d = new Destination(input.readUTF(), input.readUTF(), input.readDouble(), input.readDouble(), input.readDouble(), input.readFloat(), input.readFloat())
      new Reservation(id, player, channel, d, java.time.Instant.ofEpochSecond(input.readLong(), input.readInt()))
    }
}
