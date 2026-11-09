package io.linkmc.core

import io.linkmc.api.LinkApi
import io.linkmc.api.LinkApi.{Change, Channel, Destination, Reservation, Session, Transaction}
import io.linkmc.core.Database.{instant, lock, sql, update}

import java.sql.{Connection, ResultSet, SQLException}
import java.util.{ArrayList, Collection, List, Optional, UUID}
import scala.util.Using

/** DB reservations, not Redis counters, enforce both node and world capacities. */
final class NetworkStore(db: Database) {
  @throws[Exception]
  def heartbeat(node: String, capacity: Int): Unit = {
    LinkApi.requireName(node)
    if (capacity < 1) throw new IllegalArgumentException("Invalid capacity")
    db.transaction[AnyRef] { c =>
      lock(c, "allocation")
      update(c, "INSERT INTO link_nodes(id,capacity) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET capacity=excluded.capacity,heartbeat=clock_timestamp()", node, capacity)
      null
    }
  }

  @throws[Exception]
  def register(id: String, spawn: Destination, capacity: Int): Unit = {
    LinkApi.requireName(id)
    if (capacity < 1) throw new IllegalArgumentException("Invalid capacity")
    db.transaction[AnyRef] { c =>
      lock(c, "allocation")
      Using.resource(sql(c, "SELECT node,world FROM link_channels WHERE id=?", id)) { check =>
        Using.resource(check.executeQuery()) { row =>
          if (row.next() && (!row.getString(1).equals(spawn.node()) || !row.getString(2).equals(spawn.world()))) throw new ConflictException("Channel ID belongs to another world")
        }
      }
      var channel = id
      Using.resource(sql(c, "SELECT id FROM link_channels WHERE node=? AND world=?", spawn.node(), spawn.world())) { check =>
        Using.resource(check.executeQuery()) { row =>
          if (row.next()) channel = row.getString(1)
        }
      }
      update(c, "INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch) VALUES (?,?,?,?,true,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET capacity=excluded.capacity,ready=true,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch",
        channel, spawn.node(), spawn.world(), capacity, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch())

      null
    }
  }

  @throws[Exception]
  def ready(id: String, ready: Boolean): Unit =
    db.transaction[AnyRef] { c => lock(c, "allocation"); update(c, "UPDATE link_channels SET ready=? WHERE id=?", ready, id); null }

  @throws[Exception]
  def reconcileNode(nodeLease: Session): Unit =
    db.transaction[AnyRef] { c =>
      SessionStore.requireActive(c, nodeLease); lock(c, "allocation")
      update(c, "UPDATE link_channels SET ready=false WHERE node=?", nodeLease.node())
      update(c, "DELETE FROM link_reservations r USING link_channels c WHERE r.channel=c.id AND c.node=? AND (r.expires_at<=clock_timestamp() OR NOT EXISTS(SELECT 1 FROM link_sessions s WHERE s.player=r.player AND s.state IN ('ACTIVE','MOVING') AND s.expires_at>clock_timestamp()))", nodeLease.node())
      null
    }

  @throws[Exception]
  def channels(): List[Channel] =
    channels(false)

  @throws[Exception]
  def spawnChannels(): List[Channel] = channels(true)

  @throws[Exception]
  private def channels(spawnOnly: Boolean): List[Channel] =
    db.transaction[List[Channel]] { c =>
      val result: List[Channel] = new ArrayList[Channel]()
      Using.resource(sql(c, "SELECT c.id,c.node,c.world,c.capacity,count(r.id) FILTER(WHERE r.state='ADMITTED') AS occupied,count(DISTINCT r.player)-count(DISTINCT r.player) FILTER(WHERE r.state='ADMITTED') AS reserved FROM link_channels c JOIN link_nodes n ON n.id=c.node LEFT JOIN link_reservations r ON r.channel=c.id AND r.expires_at>clock_timestamp() WHERE c.ready AND " + (if (spawnOnly) "c.spawn_enabled AND " else "") + "n.heartbeat>clock_timestamp()-interval '15 seconds' GROUP BY c.id,c.node,c.world,c.capacity ORDER BY c.id")) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) result.add(new Channel(r.getString("id"), r.getString("node"), r.getString("world"), r.getInt("capacity"), r.getInt("occupied"), r.getInt("reserved")))
        }
      }
      List.copyOf(result)
    }

  @throws[Exception]
  def reserve(player: UUID, request: UUID): Reservation =
    reserve(player, request, null)

  @throws[Exception]
  def reserve(player: UUID, request: UUID, target: Destination): Reservation =
    db.transaction[Reservation](c => reserve(c, player, request, target, null))

  @throws[Exception]
  private[core] def reserveGroup(c: Connection, player: UUID, request: UUID, group: String): Reservation = reserve(c, player, request, null, group)

  @throws[Exception]
  private[core] def reserve(c: Connection, player: UUID, request: UUID, target: Destination, group: String): Reservation = {
    lock(c, "allocation")
    // Idempotent retries return the same destination; no extra slot is consumed.
    val existing: Reservation = Using.resource(sql(c, "SELECT r.*,c.node,c.world,c.x,c.y,c.z,c.yaw,c.pitch,r.expires_at>clock_timestamp() AS valid FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE r.id=?", request)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next()) {
          if (!player.equals(r.getObject("player", classOf[UUID]))) throw new ConflictException("Reservation ID reused")
          if (!r.getString("state").equals("RESERVED")) throw new ConflictException("Already admitted")
          if (!r.getBoolean("valid")) throw new ConflictException("Reservation expired")
          if (target != null && !NetworkStore.reservation(r).destination().equals(target)) throw new ConflictException("Reservation destination changed")
          if (group != null) {
            Using.resource(sql(c, "SELECT 1 FROM link_group_worlds WHERE id=? AND group_name=?", r.getString("channel"), group)) { check =>
              Using.resource(check.executeQuery()) { found =>
                if (!found.next()) throw new ConflictException("Reservation group changed")
              }
            }
          }
          NetworkStore.reservation(r)
        } else null
      }
    }
    if (existing != null) return existing
    // Expired admitted sessions are NOT silently freed: the backend must
    // stop simulation before releasing its admission. Dead nodes are excluded.
    update(c, "DELETE FROM link_reservations WHERE state='RESERVED' AND expires_at<=clock_timestamp()")
    Using.resource(sql(c, "SELECT 1 FROM link_reservations WHERE player=? AND state='RESERVED'", player)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (r.next()) throw new ConflictException("Player already has an admission")
      }
    }
    var query = "SELECT c.* FROM link_channels c JOIN link_nodes n ON n.id=c.node WHERE c.ready AND n.heartbeat>clock_timestamp()-interval '15 seconds' AND (SELECT count(DISTINCT r.player) FROM link_reservations r WHERE r.channel=c.id AND r.player<>?)<c.capacity AND (SELECT count(DISTINCT r.player) FROM link_reservations r JOIN link_channels nc ON nc.id=r.channel WHERE nc.node=c.node AND r.player<>?)<n.capacity ORDER BY (SELECT count(*) FROM link_reservations r WHERE r.channel=c.id) DESC,c.id LIMIT 1"
    if (target != null) query = query.replace("WHERE c.ready AND", "WHERE c.node=? AND c.world=? AND c.ready AND")
    else if (group != null) query = query.replace("WHERE c.ready AND", "WHERE EXISTS(SELECT 1 FROM link_group_worlds gw WHERE gw.id=c.id AND gw.group_name=? AND gw.state='READY') AND c.ready AND")
    else query = query.replace("WHERE c.ready AND", "WHERE c.spawn_enabled AND c.ready AND")
    // Existing players prefer their current backend; first admission still balances normally.
    if (target == null) query = query.replace("ORDER BY (SELECT", "ORDER BY CASE WHEN c.node=(SELECT node FROM link_sessions WHERE player=? AND state='ACTIVE' AND expires_at>clock_timestamp()) THEN 0 ELSE 1 END, (SELECT")
    Using.resource(if (target != null) sql(c, query, target.node(), target.world(), player, player) else if (group != null) sql(c, query, group, player, player, player) else sql(c, query, player, player, player)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (!r.next()) throw new ConflictException("NO_CAPACITY: provision a ready spawn before retrying")
        val channel = r.getString("id")
        val destination: Destination = if (target != null) target else new Destination(r.getString("node"), r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch"))
        Using.resource(sql(c, "INSERT INTO link_reservations(id,player,channel,state,expires_at,x,y,z,yaw,pitch) VALUES (?,?,?,'RESERVED',clock_timestamp()+interval '30 seconds',?,?,?,?,?) RETURNING expires_at", request, player, channel, destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch())) { insert =>
          Using.resource(insert.executeQuery()) { expiry =>
            expiry.next(); new Reservation(request, player, channel, destination, instant(expiry, 1))
          }
        }
      }
    }

  }

  @throws[Exception]
  def admit(reservation: UUID, owner: Session): Destination =
    db.transaction[Destination](c => admit(c, reservation, owner))

  @throws[Exception]
  private[core] def admit(c: java.sql.Connection, reservation: UUID, owner: Session): Destination = {
    // Match session -> allocation lock order everywhere.
    SessionStore.requireActive(c, owner); lock(c, "allocation")
    Using.resource(sql(c, "SELECT r.*,c.node,c.world,c.x,c.y,c.z,c.yaw,c.pitch,c.ready,n.heartbeat>clock_timestamp()-interval '15 seconds' AS alive,r.expires_at>clock_timestamp() AS valid FROM link_reservations r JOIN link_channels c ON c.id=r.channel JOIN link_nodes n ON n.id=c.node WHERE r.id=? AND r.player=? FOR UPDATE OF r", reservation, owner.player())) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (!r.next() || !r.getString("node").equals(owner.node()) || !r.getBoolean("ready") || !r.getBoolean("alive") || !r.getBoolean("valid"))
          throw new ConflictException("Invalid destination reservation")
        // The target already owns the fenced session. Only now release
        // its previous admission, in the same transaction as target entry.
        update(c, "DELETE FROM link_reservations WHERE player=? AND state='ADMITTED' AND id<>?", owner.player(), reservation)
        update(c, "UPDATE link_reservations SET state='ADMITTED',expires_at='infinity' WHERE id=?", reservation)
        NetworkStore.reservation(r).destination()
      }
    }

  }

  @throws[Exception]
  def cancel(id: UUID, player: UUID): Unit =
    db.transaction[AnyRef] { c => lock(c, "allocation"); update(c, "DELETE FROM link_reservations WHERE id=? AND player=? AND state='RESERVED'", id, player); null }

  @throws[Exception]
  def depart(owner: Session): Unit =
    db.transaction[AnyRef] { c =>
      SessionStore.requireActive(c, owner); lock(c, "allocation")
      update(c, "DELETE FROM link_reservations r USING link_channels c WHERE r.channel=c.id AND r.player=? AND c.node=?", owner.player(), owner.node()); null
    }

  @throws[Exception]
  def channelCount(node: String): Int =
    db.transaction[java.lang.Integer] { c =>
      Using.resource(sql(c, "SELECT count(*) FROM link_channels WHERE node=? AND spawn_enabled", node)) { s =>
        Using.resource(s.executeQuery()) { r => r.next(); Int.box(r.getInt(1)) }
      }
    }.intValue

  @throws[Exception]
  def warp(name: String): Optional[Destination] = {
    LinkApi.requireName(name)
    db.transaction[Optional[Destination]] { c =>
      Using.resource(sql(c, "SELECT * FROM link_warps WHERE name=?", name)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) Optional.of(new Destination(r.getString("node"), r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch"))) else Optional.empty[Destination]()
        }
      }
    }
  }

  @throws[Exception]
  def setWarp(name: String, d: Destination): Unit =
    setWarp(name, d, 0)

  @throws[Exception]
  def setWarp(name: String, d: Destination, capacity: Int): Unit = {
    LinkApi.requireName(name)
    if (capacity < 0) throw new IllegalArgumentException("Invalid capacity")
    db.transaction[AnyRef] { c =>
      lock(c, "warp:" + name)
      Using.resource(sql(c, "SELECT 1 FROM link_warp_groups WHERE name=?", name)) { check =>
        Using.resource(check.executeQuery()) { row => if (row.next()) throw new ConflictException("Warp name belongs to a template group") }
      }
      if (capacity > 0) {
        lock(c, "allocation")
        val exists: Boolean = Using.resource(sql(c, "SELECT 1 FROM link_channels WHERE node=? AND world=?", d.node(), d.world())) { s =>
          Using.resource(s.executeQuery()) { r => r.next() }
        }
        if (!exists) {
          val id = "warp-" + UUID.randomUUID()
          update(c, "INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch,spawn_enabled) VALUES (?,?,?,?,true,?,?,?,?,?,false)", id, d.node(), d.world(), capacity, d.x(), d.y(), d.z(), d.yaw(), d.pitch())
        }
      }
      recordWarp(c, name, new com.google.gson.Gson().toJson(d).getBytes(java.nio.charset.StandardCharsets.UTF_8), "warp:set")
      update(c, "INSERT INTO link_warps(name,node,world,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET node=excluded.node,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch", name, d.node(), d.world(), d.x(), d.y(), d.z(), d.yaw(), d.pitch()); null
    }
  }

  @throws[Exception]
  private[core] def recordWarp(c: Connection, name: String, value: Array[Byte], `type`: String): Unit = {
    val id = LinkApi.warpStateId(name); val version: Long = EventStore.read(c, id).map[java.lang.Long](s => Long.box(s.version())).orElse(Long.box(0L)).longValue
    new EventStore(db).commit(c, new Transaction(UUID.randomUUID(), List.of[Session](), List.of[Change](new Change(id, version, 1, `type`, value))))
  }

  @throws[Exception]
  def deleteWarp(name: String): Boolean = {
    LinkApi.requireName(name)
    db.transaction[java.lang.Boolean] { c =>
      lock(c, "warp:" + name)
      lock(c, "allocation")
      var deleted = update(c, "DELETE FROM link_warps WHERE name=?", name)
      deleted += update(c, "DELETE FROM link_warp_groups WHERE name=?", name)
      if (deleted == 0) java.lang.Boolean.FALSE
      else { recordWarp(c, name, new Array[Byte](0), "warp:delete"); java.lang.Boolean.TRUE }
    }.booleanValue
  }

  @throws[Exception]
  def warps(): List[String] =
    db.transaction[List[String]] { c =>
      val names: List[String] = new ArrayList[String]()
      Using.resource(sql(c, "SELECT name FROM link_warps UNION SELECT name FROM link_warp_groups ORDER BY name")) { s =>
        Using.resource(s.executeQuery()) { r => while (r.next()) names.add(r.getString(1)) }
      }
      List.copyOf(names)
    }

  @throws[Exception]
  def syncWarpWorlds(node: String, loaded: Collection[String]): Unit =
    db.transaction[AnyRef] { c =>
      lock(c, "allocation")
      Using.resource(sql(c, "SELECT id,world,ready FROM link_channels WHERE node=? AND NOT spawn_enabled", node)) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) { val ready = loaded.contains(r.getString(2)); if (ready != r.getBoolean(3)) update(c, "UPDATE link_channels SET ready=? WHERE id=?", ready, r.getString(1)) }
        }
      }
      null
    }

  @throws[Exception]
  def closeWorld(node: String, world: String): Unit =
    db.transaction[AnyRef] { c =>
      lock(c, "allocation")
      Using.resource(sql(c, "SELECT 1 FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE c.node=? AND c.world=? AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp())", node, world)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) throw new ConflictException("World still has admissions or pending travel")
        }
      }
      update(c, "UPDATE link_channels SET ready=false WHERE node=? AND world=?", node, world); null
    }

  @throws[Exception]
  def openWarpWorld(node: String, world: String): Unit =
    db.transaction[AnyRef] { c => lock(c, "allocation"); update(c, "UPDATE link_channels SET ready=true WHERE node=? AND world=? AND NOT spawn_enabled", node, world); null }

  @throws[Exception]
  def prepareDestination(d: Destination, capacity: Int): Unit = {
    if (capacity < 1) throw new IllegalArgumentException("Invalid capacity")
    db.transaction[AnyRef] { c =>
      lock(c, "allocation")
      val exists: Boolean = Using.resource(sql(c, "SELECT 1 FROM link_channels WHERE node=? AND world=?", d.node(), d.world())) { s =>
        Using.resource(s.executeQuery()) { r => r.next() }
      }
      if (!exists)
        update(c, "INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch,spawn_enabled) VALUES (?,?,?,?,true,?,?,?,?,?,false)", "warp-" + UUID.randomUUID(), d.node(), d.world(), capacity, d.x(), d.y(), d.z(), d.yaw(), d.pitch())
      null
    }
  }
}

object NetworkStore {
  @throws[SQLException]
  private def reservation(r: ResultSet): Reservation =
    new Reservation(r.getObject("id", classOf[UUID]), r.getObject("player", classOf[UUID]), r.getString("channel"), new Destination(r.getString("node"), r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch")), instant(r, "expires_at"))
}
