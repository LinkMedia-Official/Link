package io.linkmc.core

import io.linkmc.api.LinkApi
import io.linkmc.api.LinkApi.{Destination, Session, Transfer}
import io.linkmc.core.Database.{instant, lock, sharedLock, sql, update}

import java.sql.{Connection, ResultSet, SQLException}
import java.util.{Comparator, HashSet, List, Set, UUID}
import scala.util.Using

/** DB-clock leases and fencing. Redis locks are never ownership authority. */
final class SessionStore(db: Database, leaseSeconds: Int) {
  if (leaseSeconds < 5 || leaseSeconds > 300) throw new IllegalArgumentException("Lease must be 5..300 seconds")

  @throws[Exception]
  def acquire(player: UUID, node: String): Session = {
    LinkApi.requireName(node)
    db.transaction[Session] { c => sharedLock(c, "node-startup:" + node); acquire(c, player, node) }
  }

  /** Explicit startup takeover: latest durable state survives; old epochs cannot write or renew. */
  @throws[Exception]
  def acquireNode(node: String): Session = {
    val id = SessionStore.nodeId(node)
    db.transaction[Session] { c =>
      lock(c, "node-startup:" + node)
      val owned = SessionStore.lockNodeSessions(c, node, id)
      Using.resource(sql(c, "SELECT node FROM link_sessions WHERE player=?", id)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next() && !node.equals(r.getString(1))) throw new ConflictException("Node identity collision")
        }
      }
      SessionStore.retireNode(c, node, id, owned)
      update(c, "INSERT INTO link_sessions(player,node,epoch,state,expires_at) VALUES (?,?,1,'ACTIVE',clock_timestamp()+?*interval '1 second') ON CONFLICT(player) DO UPDATE SET epoch=link_sessions.epoch+1,state='ACTIVE',expires_at=excluded.expires_at", id, node, leaseSeconds)
      SessionStore.get(c, id)
    }
  }

  /** Idempotent cleanup; a stale process must never release its replacement's ownership. */
  @throws[Exception]
  def releaseNode(owner: Session): Boolean = {
    if (!owner.player().equals(SessionStore.nodeId(owner.node()))) throw new IllegalArgumentException("Not a node lease")
    db.exclusiveNodeTransaction[java.lang.Boolean] { c =>
      lock(c, "node-startup:" + owner.node())
      val owned = SessionStore.lockNodeSessions(c, owner.node(), owner.player())
      val stale: Boolean = Using.resource(sql(c, "SELECT node,epoch,state FROM link_sessions WHERE player=?", owner.player())) { s =>
        Using.resource(s.executeQuery()) { r =>
          !r.next() || !owner.node().equals(r.getString(1)) || owner.epoch() != r.getLong(2) || !r.getString(3).equals("ACTIVE")
        }
      }
      if (stale) java.lang.Boolean.FALSE
      else {
        SessionStore.retireNode(c, owner.node(), owner.player(), owned)
        update(c, "UPDATE link_sessions SET state='RELEASED',expires_at=clock_timestamp() WHERE player=? AND epoch=?", owner.player(), owner.epoch())
        java.lang.Boolean.TRUE
      }
    }.booleanValue
  }

  @throws[Exception]
  private[core] def acquire(c: java.sql.Connection, player: UUID, node: String): Session = {
    lock(c, "session:" + player)
    Using.resource(sql(c, "SELECT state,expires_at>clock_timestamp() FROM link_sessions WHERE player=? FOR UPDATE", player)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next() && r.getBoolean(2) && (r.getString(1).equals("MOVING") || r.getString(1).equals("ACTIVE")))
          throw new ConflictException("Player already owned or transferring")
      }
    }
    // An expired owner can never publish another frame. Invalidate its unclaimed
    // transfer before issuing a new epoch, so delayed destination connections fail.
    update(c, "UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'", player)
    update(c, "UPDATE link_routes SET state='FAILED' WHERE player=? AND state='REQUESTED'", player)
    update(c, "INSERT INTO link_sessions(player,node,epoch,state,expires_at) VALUES (?,?,1,'ACTIVE',clock_timestamp()+?*interval '1 second') ON CONFLICT(player) DO UPDATE SET node=excluded.node,epoch=link_sessions.epoch+1,state='ACTIVE',expires_at=excluded.expires_at", player, node, leaseSeconds)
    SessionStore.get(c, player)

  }

  @throws[Exception]
  def renew(session: Session): Session = {
    // Renewal changes only expiry. Do not wait for the session advisory lock held
    // throughout a checkpoint; ownership changes remain serialized by that lock.
    var attempt = 0
    while (true) {
      try {
        return db.transaction[Session] { c =>
          // Check expiry only after obtaining the row: time may advance while waiting.
          Using.resource(sql(c, "SELECT player FROM link_sessions WHERE player=? FOR UPDATE", session.player())) { query =>
            Using.resource(query.executeQuery()) { rows =>
              if (!rows.next()) throw new ConflictException("Missing session")
            }
          }
          val changed = update(c, "UPDATE link_sessions SET expires_at=clock_timestamp()+?*interval '1 second' WHERE player=? AND node=? AND epoch=? AND state='ACTIVE' AND expires_at>clock_timestamp()", leaseSeconds, session.player(), session.node(), session.epoch())
          if (changed != 1) throw new ConflictException("Expired, transferred or fenced session")
          SessionStore.get(c, session.player())
        }
      } catch {
        case error: SQLException =>
          val state = error.getSQLState()
          val transientFailure = state != null && (state.equals("55P03") || state.equals("40001") || state.equals("40P01") || state.startsWith("08") || error.getErrorCode() == 1205)
          if (!transientFailure || attempt >= 2) throw error
          Thread.sleep(200L * (attempt + 1))
      }
      attempt += 1
    }
    throw new IllegalStateException("unreachable")
  }

  @throws[Exception]
  def release(session: Session): Unit =
    db.transaction[AnyRef] { c =>
      SessionStore.requireActive(c, session)
      update(c, "UPDATE link_sessions SET state='RELEASED',expires_at=clock_timestamp() WHERE player=?", session.player())
      null
    }

  @throws[Exception]
  def prepare(source: Session, operation: UUID, dest: Destination): Transfer =
    db.transaction[Transfer](c => prepare(c, source, operation, dest))

  @throws[Exception]
  private[core] def prepare(c: java.sql.Connection, source: Session, operation: UUID, dest: Destination): Transfer = {
    lock(c, "session:" + source.player())
    val existing: Transfer = Using.resource(sql(c, "SELECT * FROM link_transfers WHERE id=?", operation)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next()) {
          if (!r.getObject("player", classOf[UUID]).equals(source.player()) || r.getLong("source_epoch") != source.epoch()
              || !r.getString("source_node").equals(source.node()) || !SessionStore.destination(r).equals(dest))
            throw new ConflictException("Transfer ID reused")
          new Transfer(operation, source.player(), r.getLong("final_version"), dest)
        } else null
      }
    }
    if (existing != null) return existing
    SessionStore.requireActive(c, source)
    // Player writers take the same session lock before the aggregate lock.
    val version: Long = EventStore.read(c, "player:" + source.player()).map[java.lang.Long](s => Long.box(s.version())).orElse(Long.box(0L)).longValue
    update(c, "INSERT INTO link_transfers(id,player,source_node,source_epoch,destination_node,world,x,y,z,yaw,pitch,final_version,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED')",
      operation, source.player(), source.node(), source.epoch(), dest.node(), dest.world(), dest.x(), dest.y(), dest.z(), dest.yaw(), dest.pitch(), version)
    update(c, "UPDATE link_sessions SET state='MOVING' WHERE player=?", source.player())
    new Transfer(operation, source.player(), version, dest)
  }

  /** Release only this connection's epoch. A departed source cannot release an accepted destination. */
  @throws[Exception]
  def disconnect(owner: Session): Boolean =
    db.transaction[java.lang.Boolean] { c =>
      lock(c, "session:" + owner.player())
      val stale: Boolean = Using.resource(sql(c, "SELECT node,epoch,state FROM link_sessions WHERE player=?", owner.player())) { q =>
        Using.resource(q.executeQuery()) { r =>
          !r.next() || !r.getString(1).equals(owner.node()) || r.getLong(2) != owner.epoch() || !Set.of("ACTIVE", "MOVING").contains(r.getString(3))
        }
      }
      if (stale) java.lang.Boolean.FALSE
      else {
        lock(c, "allocation")
        update(c, "UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'", owner.player())
        update(c, "UPDATE link_routes SET state='FAILED' WHERE player=? AND state IN ('REQUESTED','READY','CONNECTING')", owner.player())
        update(c, "UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED'", owner.player())
        update(c, "DELETE FROM link_reservations WHERE player=?", owner.player())
        update(c, "UPDATE link_sessions SET state='RELEASED',expires_at=clock_timestamp() WHERE player=?", owner.player())
        java.lang.Boolean.TRUE
      }
    }.booleanValue

  /** Source can extend a frozen handoff; accepted/replaced epochs are never touched. */
  @throws[Exception]
  def renewMoving(owner: Session): Boolean =
    db.transaction[java.lang.Boolean](c => java.lang.Boolean.valueOf(update(c, "UPDATE link_sessions SET expires_at=clock_timestamp()+?*interval '1 second' WHERE player=? AND node=? AND epoch=? AND state='MOVING' AND expires_at>clock_timestamp()", leaseSeconds, owner.player(), owner.node(), owner.epoch()) == 1)).booleanValue

  @throws[Exception]
  def available(player: UUID): Boolean =
    db.transaction[java.lang.Boolean] { c =>
      val first: java.lang.Boolean = Using.resource(sql(c, "SELECT state,expires_at>clock_timestamp() FROM link_sessions WHERE player=?", player)) { q =>
        Using.resource(q.executeQuery()) { r =>
          if (!r.next() || !Set.of("ACTIVE", "MOVING").contains(r.getString(1))) java.lang.Boolean.TRUE
          else if (r.getBoolean(2)) java.lang.Boolean.FALSE
          else null
        }
      }
      if (first != null) first
      else {
        lock(c, "session:" + player)
        val second: java.lang.Boolean = Using.resource(sql(c, "SELECT state,expires_at>clock_timestamp() FROM link_sessions WHERE player=?", player)) { q =>
          Using.resource(q.executeQuery()) { r =>
            if (!r.next() || !Set.of("ACTIVE", "MOVING").contains(r.getString(1))) java.lang.Boolean.TRUE
            else if (r.getBoolean(2)) java.lang.Boolean.FALSE
            else null
          }
        }
        if (second != null) second
        else {
          // A crashed source may leave a checkpoint-pinned reservation. Its expired epoch cannot write again.
          lock(c, "allocation")
          update(c, "UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'", player)
          update(c, "UPDATE link_routes SET state='FAILED' WHERE player=? AND state IN ('REQUESTED','READY','CONNECTING')", player)
          update(c, "UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED'", player)
          update(c, "DELETE FROM link_reservations WHERE player=?", player)
          update(c, "UPDATE link_sessions SET state='RELEASED' WHERE player=?", player); java.lang.Boolean.TRUE
        }
      }
    }.booleanValue

  @throws[Exception]
  def accept(player: UUID, transfer: UUID, node: String): Session =
    db.transaction[Session] { c => sharedLock(c, "node-startup:" + node); accept(c, player, transfer, node) }

  @throws[Exception]
  private[core] def accept(c: java.sql.Connection, player: UUID, transfer: UUID, node: String): Session = {
    lock(c, "session:" + player)
    Using.resource(sql(c, "SELECT * FROM link_transfers WHERE id=? AND player=? FOR UPDATE", transfer, player)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (!r.next() || !r.getString("destination_node").equals(node)) throw new ConflictException("Wrong transfer destination")
        if (r.getString("state").equals("ACCEPTED")) {
          val current = SessionStore.get(c, player)
          if (current.epoch() != r.getLong("accepted_epoch") || !current.node().equals(node)) throw new ConflictException("Transfer already superseded")
          SessionStore.requireActive(c, current)
          current
        } else {
          if (!r.getString("state").equals("PREPARED")) throw new ConflictException("Transfer cancelled")
          val changed = update(c, "UPDATE link_sessions SET node=?,epoch=epoch+1,state='ACTIVE',expires_at=clock_timestamp()+?*interval '1 second' WHERE player=? AND state='MOVING' AND epoch=? AND node=? AND expires_at>clock_timestamp()",
            node, leaseSeconds, player, r.getLong("source_epoch"), r.getString("source_node"))
          if (changed != 1) throw new ConflictException("Transfer ownership mismatch")
          val current = SessionStore.get(c, player)
          update(c, "UPDATE link_transfers SET state='ACCEPTED',accepted_epoch=? WHERE id=?", current.epoch(), transfer)
          current
        }
      }
    }

  }
}

object SessionStore {
  def nodeId(node: String): UUID = { LinkApi.requireName(node); UUID.nameUUIDFromBytes(("link-node:" + node).getBytes(java.nio.charset.StandardCharsets.UTF_8)) }

  @throws[Exception]
  private def lockNodeSessions(c: Connection, node: String, id: UUID): List[UUID] = {
    val ids: Set[UUID] = new HashSet[UUID](); ids.add(id)
    Using.resource(sql(c, "SELECT player FROM link_sessions WHERE node=?", node)) { s =>
      Using.resource(s.executeQuery()) { r => while (r.next()) ids.add(r.getObject(1, classOf[UUID])) }
    }
    val ordered: List[UUID] = ids.stream().sorted(Comparator.comparing[UUID, String](u => u.toString)).toList()
    val it = ordered.iterator()
    while (it.hasNext) { val player = it.next(); lock(c, "session:" + player) }
    ordered
  }

  @throws[Exception]
  private def retireNode(c: Connection, node: String, nodeId: UUID, owned: List[UUID]): Unit = {
    lock(c, "allocation")
    val it = owned.iterator()
    while (it.hasNext) {
      val player = it.next()
      if (!player.equals(nodeId)) {
        val retired = update(c, "UPDATE link_sessions SET epoch=epoch+1,state='RELEASED',expires_at=clock_timestamp() WHERE player=? AND node=? AND state IN ('ACTIVE','MOVING')", player, node)
        if (retired != 0) {
          update(c, "UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'", player)
          update(c, "UPDATE link_routes SET state='FAILED' WHERE player=? AND state IN ('REQUESTED','READY','CONNECTING')", player)
          update(c, "UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED'", player)
          update(c, "DELETE FROM link_reservations WHERE player=?", player)
        }
      }
    }
    update(c, "UPDATE link_channels SET ready=false WHERE node=?", node)
    update(c, "UPDATE link_arrivals SET state='CANCELLED' WHERE node=? AND state='ISSUED'", node)
    update(c, "UPDATE link_routes SET state='FAILED' WHERE state IN ('REQUESTED','READY','CONNECTING') AND reservation IN (SELECT r.id FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE c.node=?)", node)
    update(c, "DELETE FROM link_reservations r USING link_channels c WHERE r.channel=c.id AND c.node=?", node)
  }

  @throws[SQLException]
  private[core] def requireActive(c: Connection, session: Session): Unit = {
    lock(c, "session:" + session.player())
    Using.resource(sql(c, "SELECT node,epoch,state,expires_at>clock_timestamp() FROM link_sessions WHERE player=?", session.player())) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (!r.next() || !r.getString(1).equals(session.node()) || r.getLong(2) != session.epoch()
            || !r.getString(3).equals("ACTIVE") || !r.getBoolean(4))
          throw new ConflictException("Expired, transferred or fenced session")
      }
    }
  }

  @throws[SQLException]
  private[core] def get(c: Connection, player: UUID): Session =
    Using.resource(sql(c, "SELECT node,epoch,expires_at FROM link_sessions WHERE player=?", player)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (!r.next()) throw new ConflictException("Missing session")
        new Session(player, r.getString(1), r.getLong(2), instant(r, 3))
      }
    }

  @throws[SQLException]
  private[core] def destination(r: ResultSet): Destination =
    new Destination(r.getString("destination_node"), r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch"))
}
