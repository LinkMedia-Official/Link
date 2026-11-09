package io.linkmc.core

import io.linkmc.api.LinkApi
import io.linkmc.api.LinkApi.{Change, Channel, ChannelPolicy, Destination, Reservation, Session, Transaction, WarpGroup}
import io.linkmc.core.Database.{instant, lock, sql, update}

import java.sql.{Connection, ResultSet, SQLException}
import java.util.{ArrayList, HashSet, List, Locale, Map, Optional, Set, UUID}
import scala.util.Using

/** Durable group requests and node-bound creation jobs. All allocation decisions share the capacity lock. */
final class WarpGroupStore(db: Database) {
  private val network: NetworkStore = new NetworkStore(db)

  @throws[Exception]
  def list(): List[WarpGroup] =
    db.transaction[List[WarpGroup]] { c =>
      val result = new ArrayList[WarpGroup]()
      Using.resource(sql(c, "SELECT * FROM link_warp_groups ORDER BY name")) { s =>
        Using.resource(s.executeQuery()) { r => while (r.next()) result.add(WarpGroupStore.group(r)) }
      }
      List.copyOf(result)
    }

  @throws[Exception]
  def channels(name: String): List[Channel] = {
    LinkApi.requireName(name); val ids: Set[String] = db.transaction[Set[String]] { c =>
      val result: Set[String] = new HashSet[String]()
      Using.resource(sql(c, "SELECT id FROM link_group_worlds WHERE group_name=?", name)) { s =>
        Using.resource(s.executeQuery()) { r => while (r.next()) result.add(r.getString(1)) }
      }
      result
    }
    network.channels().stream().filter(ch => ids.contains(ch.id())).toList()
  }

  @throws[Exception]
  def exists(name: String): Boolean =
    db.transaction[java.lang.Boolean] { c =>
      Using.resource(sql(c, "SELECT 1 FROM link_warp_groups WHERE name=?", name)) { s =>
        Using.resource(s.executeQuery()) { r => Boolean.box(r.next()) }
      }
    }

  @throws[Exception]
  def bind(name: String, template: String, version: Int, node: String): Unit = {
    LinkApi.requireName(name); LinkApi.requireName(template); LinkApi.requireName(node); if (version < 1) throw new IllegalArgumentException("Invalid template version")
    db.transaction[AnyRef] { c =>
      lock(c, "warp:" + name); lock(c, "allocation")
      Using.resource(sql(c, "SELECT 1 FROM link_templates WHERE name=? AND version=?", template, Int.box(version))) { s =>
        Using.resource(s.executeQuery()) { r => if (!r.next()) throw new ConflictException("Publish the template first") }
      }
      val policy = TemplateStore.policy(c, template, version); val desired = new WarpGroup(name, template, version, node, policy)
      val unchanged = Using.resource(sql(c, "SELECT * FROM link_warp_groups WHERE name=?", name)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) { if (WarpGroupStore.group(r).equals(desired)) true else throw new ConflictException("Existing group is immutable; use a new warp name") }
          else false
        }
      }
      if (!unchanged) {
        if (EventStore.read(c, LinkApi.warpStateId(name)).isPresent()) throw new ConflictException("Previously used warp names cannot become a new group")
        Using.resource(sql(c, "SELECT name FROM link_warps WHERE name=? UNION SELECT group_name FROM link_group_worlds WHERE group_name=?", name, name)) { s =>
          Using.resource(s.executeQuery()) { r => if (r.next()) throw new ConflictException("Warp name already in use or retired") }
        }
        update(c, "INSERT INTO link_warp_groups(name,template,version,home_node,capacity,max_channels,auto_expand,network_wide) VALUES (?,?,?,?,?,?,?,?)", name, template, Int.box(version), node, Int.box(policy.capacity()), Int.box(policy.maxChannels()), Boolean.box(policy.autoExpand()), Boolean.box(policy.networkWide()))
        network.recordWarp(c, name, new com.google.gson.Gson().toJson(desired).getBytes(java.nio.charset.StandardCharsets.UTF_8), "warp:group")
      }
      null
    }
  }

  @throws[Exception]
  def enqueue(player: UUID, id: UUID, name: String): Unit = {
    LinkApi.requireName(name); db.transaction[AnyRef] { c =>
      val owner: Session = SessionStore.get(c, player); SessionStore.requireActive(c, owner); lock(c, "allocation")
      val reused = Using.resource(sql(c, "SELECT player,group_name FROM link_group_requests WHERE id=?", id)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) { if (!player.equals(r.getObject(1, classOf[UUID])) || !name.equals(r.getString(2))) throw new ConflictException("Group request ID reused"); true }
          else false
        }
      }
      if (!reused) {
        Using.resource(sql(c, "SELECT 1 FROM link_warp_groups WHERE name=?", name)) { s =>
          Using.resource(s.executeQuery()) { r => if (!r.next()) throw new ConflictException("Unknown warp group") }
        }
        Using.resource(sql(c, "SELECT 1 FROM link_group_requests WHERE player=? AND state='WAITING' AND expires_at>clock_timestamp()", player)) { s =>
          Using.resource(s.executeQuery()) { r => if (r.next()) throw new ConflictException("Already waiting for a channel") }
        }
        update(c, "INSERT INTO link_group_requests(id,player,group_name,node,epoch,state,expires_at) VALUES (?,?,?,?,?,'WAITING',clock_timestamp()+?*interval '1 second')", id, player, name, owner.node(), Long.box(owner.epoch()), Int.box(60))
        recordRequest(c, id)
      }
      null
    }
  }

  @throws[Exception]
  private def recordRequest(c: Connection, id: UUID): Unit = {
    Using.resource(sql(c, "SELECT * FROM link_group_requests WHERE id=?", id)) { s =>
      Using.resource(s.executeQuery()) { r =>
        r.next(); val key = "group-request:" + r.getObject("player", classOf[UUID]); val state = r.getString("state")
        val value = Map.of[String, AnyRef]("id", id.toString, "group", r.getString("group_name"), "node", r.getString("node"), "epoch", Long.box(r.getLong("epoch")), "state", state, "expiresAt", instant(r, "expires_at").toString)
        val version: Long = EventStore.read(c, key).map[java.lang.Long](st => Long.box(st.version())).orElse(Long.box(0L))
        val operation = UUID.nameUUIDFromBytes(("group:" + id + ":" + state).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        new EventStore(db).commit(c, new Transaction(operation, List.of[Session](), List.of[Change](new Change(key, version, 1, "group:" + state.toLowerCase(Locale.ROOT), new com.google.gson.Gson().toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
      }
    }
  }

  @throws[Exception]
  def pending(node: String): List[WarpGroupStore.Pending] =
    db.transaction[List[WarpGroupStore.Pending]] { c =>
      val rows = new ArrayList[WarpGroupStore.Pending]()
      Using.resource(sql(c, "SELECT * FROM link_group_requests WHERE node=? AND state='WAITING' ORDER BY created_at LIMIT 64", node)) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) rows.add(WarpGroupStore.Pending(r.getObject("id", classOf[UUID]), r.getObject("player", classOf[UUID]), r.getString("group_name"), r.getString("node"), r.getLong("epoch"), r.getString("state")))
        }
      }
      rows
    }

  /** Reservation, route, request transition and event commit together. Empty means waiting or rejected. */
  @throws[Exception]
  def dispatch(pending: WarpGroupStore.Pending): Optional[RouteStore.Request] =
    db.transaction[Optional[RouteStore.Request]] { c =>
      lock(c, "session:" + pending.player); lock(c, "allocation")
      val waiting = Using.resource(sql(c, "SELECT q.state,q.expires_at>clock_timestamp() AS valid,s.state AS session_state,s.epoch,s.node, s.expires_at>clock_timestamp() AS session_valid FROM link_group_requests q LEFT JOIN link_sessions s ON s.player=q.player WHERE q.id=?", pending.id)) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (!r.next() || !r.getString("state").equals("WAITING")) false
          else {
            var active = r.getBoolean("valid") && r.getBoolean("session_valid") && "ACTIVE".equals(r.getString("session_state")) && r.getLong("epoch") == pending.epoch && pending.node.equals(r.getString("node"))
            Using.resource(sql(c, "SELECT 1 FROM link_warp_groups WHERE name=?", pending.group)) { check =>
              Using.resource(check.executeQuery()) { g => active &= g.next() }
            }
            if (!active) { update(c, "UPDATE link_group_requests SET state='FAILED' WHERE id=?", pending.id); recordRequest(c, pending.id); false }
            else true
          }
        }
      }
      if (!waiting) Optional.empty[RouteStore.Request]()
      else {
        val reserved: Option[Reservation] =
          try Some(network.reserveGroup(c, pending.player, pending.id, pending.group))
          catch { case e: ConflictException => if (e.getMessage().startsWith("NO_CAPACITY") || e.getMessage().equals("Player already has an admission")) None else throw e }
        reserved match {
          case None => Optional.empty[RouteStore.Request]()
          case Some(reservation) =>
            update(c, "INSERT INTO link_routes(id,player,reservation,state) VALUES (?,?,?,'REQUESTED')", pending.id, pending.player, reservation.id())
            update(c, "UPDATE link_group_requests SET state='DISPATCHED' WHERE id=?", pending.id); recordRequest(c, pending.id); Optional.of(new RouteStore.Request(pending.id, reservation))
        }
      }
    }

  @throws[Exception]
  def status(id: UUID): String =
    db.transaction[String] { c =>
      Using.resource(sql(c, "SELECT state FROM link_group_requests WHERE id=?", id)) { s =>
        Using.resource(s.executeQuery()) { r => if (r.next()) r.getString(1) else "MISSING" }
      }
    }

  /** At most one unfinished creation per group, under the global allocation lock. */
  @throws[Exception]
  def provisionDemand(): Unit = {
    val groups = list().iterator()
    while (groups.hasNext) {
      val group = groups.next()
      db.transaction[AnyRef] { c =>
        lock(c, "allocation")
        val known = Using.resource(sql(c, "SELECT 1 FROM link_warp_groups WHERE name=?", group.name())) { s =>
          Using.resource(s.executeQuery()) { r => r.next() }
        }
        if (known) {
          val waiting = Using.resource(sql(c, "SELECT 1 FROM link_group_requests q JOIN link_sessions s ON s.player=q.player WHERE q.group_name=? AND q.state='WAITING' AND q.expires_at>clock_timestamp() AND s.state='ACTIVE' AND s.epoch=q.epoch AND s.expires_at>clock_timestamp()", group.name())) { s =>
            Using.resource(s.executeQuery()) { r => r.next() }
          }
          if (waiting) {
            var count = 0; var creating = false
            Using.resource(sql(c, "SELECT state FROM link_group_worlds WHERE group_name=?", group.name())) { s =>
              Using.resource(s.executeQuery()) { r => while (!creating && r.next()) { count += 1; if (r.getString(1).equals("CREATING")) creating = true } }
            }
            if (!creating && !(count >= (if (group.policy().autoExpand()) group.policy().maxChannels() else 1))) {
              val room = "SELECT 1 FROM link_channels c JOIN link_nodes n ON n.id=c.node JOIN link_group_worlds g ON g.id=c.id WHERE g.group_name=? AND g.state='READY' AND c.ready AND n.heartbeat>clock_timestamp()-interval '15 seconds' AND (SELECT count(DISTINCT r.player) FROM link_reservations r WHERE r.channel=c.id AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp()))<c.capacity AND (SELECT count(DISTINCT r.player) FROM link_reservations r JOIN link_channels nc ON nc.id=r.channel WHERE nc.node=c.node AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp()))<n.capacity"
              val hasRoom = Using.resource(sql(c, room, group.name())) { s =>
                Using.resource(s.executeQuery()) { r => r.next() }
              }
              if (!hasRoom) {
                val query = "SELECT n.id FROM link_nodes n WHERE n.heartbeat>clock_timestamp()-interval '15 seconds' AND (SELECT count(DISTINCT r.player) FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE c.node=n.id AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp()))<n.capacity " + (if (group.policy().networkWide()) "" else "AND n.id=? ") + "ORDER BY (SELECT count(*) FROM link_group_worlds w WHERE w.node=n.id),n.id LIMIT 1"
                val found: Option[String] = Using.resource(if (group.policy().networkWide()) sql(c, query) else sql(c, query, group.homeNode())) { s =>
                  Using.resource(s.executeQuery()) { r => if (!r.next()) None else Some(r.getString(1)) }
                }
                found match {
                  case Some(node) =>
                    val id = "group-" + UUID.randomUUID(); update(c, "INSERT INTO link_group_worlds(id,group_name,node,world,state) VALUES (?,?,?,?,'CREATING')", id, group.name(), node, id)
                    journalWorld(c, id, "CREATING")
                  case None =>
                }
              }
            }
          }
        }
        null
      }
    }
  }

  @throws[Exception]
  def jobs(node: String): List[WarpGroupStore.Job] =
    db.transaction[List[WarpGroupStore.Job]] { c =>
      val result = new ArrayList[WarpGroupStore.Job]()
      Using.resource(sql(c, "SELECT w.*,g.template,g.version,g.capacity FROM link_group_worlds w JOIN link_warp_groups g ON g.name=w.group_name WHERE w.node=? AND w.state='CREATING' ORDER BY w.id LIMIT 4", node)) { s =>
        Using.resource(s.executeQuery()) { r =>
          while (r.next()) result.add(WarpGroupStore.Job(r.getString("id"), r.getString("group_name"), node, r.getString("world"), r.getString("template"), r.getInt("version"), r.getInt("capacity")))
        }
      }
      result
    }

  @throws[Exception]
  def ready(job: WarpGroupStore.Job, spawn: Destination, owner: Session): Unit = {
    if (!job.node.equals(spawn.node()) || !job.world.equals(spawn.world()) || !owner.node().equals(job.node)) throw new IllegalArgumentException("Wrong group destination")
    db.transaction[AnyRef] { c =>
      SessionStore.requireActive(c, owner); lock(c, "allocation")
      val creating = Using.resource(sql(c, "SELECT 1 FROM link_group_worlds w JOIN link_warp_groups g ON g.name=w.group_name WHERE w.id=? AND w.state='CREATING'", job.id)) { s =>
        Using.resource(s.executeQuery()) { r => r.next() }
      }
      if (creating) {
        update(c, "INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch,spawn_enabled) VALUES (?,?,?,?,true,?,?,?,?,?,false) ON CONFLICT(id) DO UPDATE SET ready=true", job.id, job.node, job.world, Int.box(job.capacity), Double.box(spawn.x()), Double.box(spawn.y()), Double.box(spawn.z()), Float.box(spawn.yaw()), Float.box(spawn.pitch()))
        update(c, "UPDATE link_group_worlds SET state='READY' WHERE id=?", job.id); journalWorld(c, job.id, "READY")
      }
      null
    }
  }

  @throws[Exception]
  private def journalWorld(c: Connection, id: String, state: String): Unit = {
    val key = "group-world:" + id; val version: Long = EventStore.read(c, key).map[java.lang.Long](st => Long.box(st.version())).orElse(Long.box(0L))
    Using.resource(sql(c, "SELECT w.*,g.template,g.version,g.capacity FROM link_group_worlds w JOIN link_warp_groups g ON g.name=w.group_name WHERE w.id=?", id)) { query =>
      Using.resource(query.executeQuery()) { row =>
        row.next(); val value = Map.of[String, AnyRef]("id", id, "group", row.getString("group_name"), "node", row.getString("node"), "world", row.getString("world"), "template", row.getString("template"), "version", Int.box(row.getInt("version")), "capacity", Int.box(row.getInt("capacity")), "state", state)
        new EventStore(db).commit(c, new Transaction(UUID.randomUUID(), List.of[Session](), List.of[Change](new Change(key, version, 1, "group-world:" + state.toLowerCase(Locale.ROOT), new com.google.gson.Gson().toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
      }
    }
  }
}

object WarpGroupStore {
  final case class Job(id: String, group: String, node: String, world: String, template: String, version: Int, capacity: Int)
  final case class Pending(id: UUID, player: UUID, group: String, node: String, epoch: Long, state: String)

  @throws[SQLException]
  private def group(r: ResultSet): WarpGroup =
    new WarpGroup(r.getString("name"), r.getString("template"), r.getInt("version"), r.getString("home_node"), new ChannelPolicy(r.getInt("capacity"), r.getInt("max_channels"), r.getBoolean("auto_expand"), r.getBoolean("network_wide")))
}
