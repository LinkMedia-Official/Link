package io.linkmc.core

import io.linkmc.api.LinkApi.{Change, Destination, Session, TeleportRequest, Transaction}
import io.linkmc.core.Database.{instant, lock, sql, update}
import io.linkmc.core.TeleportStore.{Rejected, Row, Work, active, epoch, now, pair, username}

import java.sql.{Connection, Timestamp}
import java.time.Instant
import java.util.{ArrayList, LinkedHashMap, List, Locale, Set, UUID}
import scala.util.Using

/** Consent-bound requests. A request is bound to both login/session epochs. */
final class TeleportStore(db: Database) {
  @throws[Exception]
  def registerPlayer(player: UUID, name: String): Unit = {
    username(name); db.transaction[AnyRef] { c => update(c, "INSERT INTO link_player_names(player,name,lower_name) VALUES (?,?,?) ON CONFLICT(player) DO UPDATE SET name=excluded.name,lower_name=excluded.lower_name", player, name, name.toLowerCase(Locale.ROOT)); null }
  }

  @throws[Exception]
  private def online(c: Connection, name: String): UUID = {
    username(name); var result: UUID = null
    Using.resource(sql(c, "SELECT p.player FROM link_player_names p JOIN link_sessions s ON s.player=p.player JOIN link_nodes n ON n.id=s.node WHERE p.lower_name=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp() AND n.heartbeat>clock_timestamp()-interval '15 seconds'", name.toLowerCase(Locale.ROOT))) { s =>
      Using.resource(s.executeQuery()) { r =>
        while (r.next()) { if (result != null) throw new Rejected("동일한 이름의 접속 정보가 여러 개입니다."); result = r.getObject(1, classOf[UUID]) }
      }
    }
    if (result == null) throw new Rejected("접속 중인 플레이어를 찾을 수 없습니다: " + name); result
  }

  @throws[Exception]
  def onlinePlayers(): List[String] =
    db.transaction[List[String]] { c =>
      val names: List[String] = new ArrayList[String]()
      Using.resource(sql(c, "SELECT p.name FROM link_player_names p JOIN link_sessions s ON s.player=p.player JOIN link_nodes n ON n.id=s.node WHERE s.state='ACTIVE' AND s.expires_at>clock_timestamp() AND n.heartbeat>clock_timestamp()-interval '15 seconds' ORDER BY p.lower_name")) { s =>
        Using.resource(s.executeQuery()) { r => while (r.next()) names.add(r.getString(1)) }
      }
      List.copyOf(names)
    }

  @throws[Exception]
  private def rows(c: Connection, where: String, args: AnyRef*): List[Row] = {
    val result: List[Row] = new ArrayList[Row]()
    Using.resource(sql(c, "SELECT q.*,a.name AS from_name,b.name AS to_name,q.expires_at>clock_timestamp() AS valid FROM link_teleport_requests q JOIN link_player_names a ON a.player=q.requester JOIN link_player_names b ON b.player=q.recipient WHERE " + where, args*)) { s =>
      Using.resource(s.executeQuery()) { r =>
        while (r.next()) {
          val valid = r.getBoolean("valid"); var state = r.getString("state"); if (!valid && Set.of("PENDING", "ACCEPTED", "RESOLVED").contains(state)) state = "EXPIRED"
          val request = new TeleportRequest(r.getObject("id", classOf[UUID]), r.getObject("requester", classOf[UUID]), r.getString("from_name"), r.getObject("recipient", classOf[UUID]), r.getString("to_name"), r.getBoolean("here"), state, instant(r, "expires_at"))
          val d: Destination = if (r.getString("destination_node") == null) null else new Destination(r.getString("destination_node"), r.getString("destination_world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch"))
          result.add(Row(request, r.getLong("requester_epoch"), r.getLong("recipient_epoch"), valid, d))
        }
      }
    }
    result
  }

  @throws[Exception]
  private def read(c: Connection, id: UUID): Row =
    rows(c, "q.id=?", id).stream().findFirst().orElseThrow[Rejected](() => new Rejected("티피 요청을 찾을 수 없습니다."))

  @throws[Exception]
  private def journal(c: Connection, id: UUID): Unit = {
    val row = read(c, id); val key = "tpa:" + row.request.requester()
    val version: Long = EventStore.read(c, key).map[java.lang.Long](st => Long.box(st.version())).orElse(Long.box(0L))
    val snapshot: java.util.Map[String, AnyRef] = new LinkedHashMap[String, AnyRef]()
    snapshot.put("id", id.toString); snapshot.put("requester", row.request.requester().toString); snapshot.put("requesterName", row.request.requesterName())
    snapshot.put("recipient", row.request.recipient().toString); snapshot.put("recipientName", row.request.recipientName())
    snapshot.put("requesterEpoch", Long.box(row.fromEpoch)); snapshot.put("recipientEpoch", Long.box(row.toEpoch)); snapshot.put("here", Boolean.box(row.request.here()))
    snapshot.put("state", row.request.state()); snapshot.put("expiresAt", row.request.expiresAt().toString); snapshot.put("destination", row.destination)
    val value: Array[Byte] = new com.google.gson.Gson().toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8)
    new EventStore(db).commit(c, new Transaction(UUID.randomUUID(), List.of[Session](), List.of[Change](new Change(key, version, 1, "teleport:" + row.request.state().toLowerCase(Locale.ROOT), value))))
  }

  @throws[Exception]
  def request(requester: UUID, target: String, here: Boolean): TeleportRequest =
    db.transaction[TeleportRequest] { c =>
      val recipient = online(c, target); if (requester.equals(recipient)) throw new Rejected("자신에게 티피 요청을 보낼 수 없습니다.")
      pair(c, requester, recipient); val from = epoch(c, requester); val to = epoch(c, recipient); val time: Instant = now(c)
      Using.resource(sql(c, "SELECT 1 FROM link_teleport_requests WHERE requester=? AND ((state IN ('PENDING','ACCEPTED','RESOLVED') AND expires_at>clock_timestamp()) OR created_at>?)", requester, Timestamp.from(time.minusSeconds(5)))) { s =>
        Using.resource(s.executeQuery()) { r =>
          if (r.next()) throw new Rejected("진행 중인 요청이 있거나 재요청 대기 시간(5초)입니다. /티피취소로 대기 요청을 취소하세요.")
        }
      }
      Using.resource(sql(c, "SELECT count(*) FROM link_teleport_requests WHERE recipient=? AND state='PENDING' AND expires_at>clock_timestamp()", recipient)) { s =>
        Using.resource(s.executeQuery()) { r => r.next(); if (r.getInt(1) >= 10) throw new Rejected("상대의 대기 요청이 너무 많습니다.") }
      }
      val id = UUID.randomUUID(); update(c, "INSERT INTO link_teleport_requests(id,requester,recipient,requester_epoch,recipient_epoch,here,state,expires_at) VALUES (?,?,?,?,?,?,'PENDING',?)", id, requester, recipient, Long.box(from), Long.box(to), Boolean.box(here), Timestamp.from(time.plusSeconds(60)))
      journal(c, id); read(c, id).request
    }

  @throws[Exception]
  def incoming(recipient: UUID): List[TeleportRequest] =
    db.transaction[List[TeleportRequest]](c => rows(c, "q.recipient=? AND q.state='PENDING' AND q.expires_at>clock_timestamp() ORDER BY q.created_at", recipient).stream().map[TeleportRequest](row => row.request).toList())

  @throws[Exception]
  def respond(recipient: UUID, requesterName: String, accept: Boolean): TeleportRequest =
    db.transaction[TeleportRequest] { c =>
      var pending = rows(c, "q.recipient=? AND q.state='PENDING' AND q.expires_at>clock_timestamp() ORDER BY q.created_at", recipient)
      if (requesterName != null) { username(requesterName); pending = pending.stream().filter(r => r.request.requesterName().equalsIgnoreCase(requesterName)).toList() }
      if (pending.isEmpty()) throw new Rejected("수락하거나 거절할 대기 요청이 없습니다.")
      if (pending.size() != 1) throw new Rejected("요청이 여러 개입니다. /티피수락 <요청자> 또는 /티피거절 <요청자>를 사용하세요.")
      val initial = pending.getFirst(); pair(c, initial.request.requester(), recipient); val row = read(c, initial.request.id())
      if (!row.request.recipient().equals(recipient) || !row.request.state().equals("PENDING")) throw new Rejected("이미 처리된 티피 요청입니다.")
      active(c, row); update(c, "UPDATE link_teleport_requests SET state=? WHERE id=?", if (accept) "ACCEPTED" else "DECLINED", row.request.id()); journal(c, row.request.id()); read(c, row.request.id()).request
    }

  @throws[Exception]
  def cancel(requester: UUID): Boolean =
    db.transaction[java.lang.Boolean] { c =>
      val pending = rows(c, "q.requester=? AND q.state='PENDING' AND q.expires_at>clock_timestamp()", requester)
      if (pending.isEmpty()) Boolean.box(false)
      else {
        val initial = pending.getFirst(); pair(c, requester, initial.request.recipient()); val row = read(c, initial.request.id())
        if (!row.request.state().equals("PENDING")) Boolean.box(false)
        else {
          update(c, "UPDATE link_teleport_requests SET state='CANCELLED' WHERE id=?", row.request.id()); journal(c, row.request.id()); Boolean.box(true)
        }
      }
    }

  @throws[Exception]
  def work(node: String): List[Work] =
    db.transaction[List[Work]](c => rows(c, "q.state IN ('ACCEPTED','RESOLVED') AND q.expires_at>clock_timestamp() AND EXISTS (SELECT 1 FROM link_sessions s WHERE s.player=CASE WHEN q.here THEN q.requester ELSE q.recipient END AND s.node=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp()) ORDER BY q.created_at LIMIT 64", node).stream().map[Work](r => Work(r.request, r.destination)).toList())

  @throws[Exception]
  def notices(node: String): List[TeleportRequest] =
    db.transaction[List[TeleportRequest]](c => rows(c, "q.created_at>? AND ((q.state='PENDING' AND q.expires_at>clock_timestamp()) OR q.id=(SELECT newest.id FROM link_teleport_requests newest WHERE newest.requester=q.requester ORDER BY newest.created_at DESC,newest.id DESC LIMIT 1)) AND EXISTS (SELECT 1 FROM link_sessions s WHERE s.player IN (q.requester,q.recipient) AND s.node=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp()) ORDER BY q.created_at DESC", Timestamp.from(now(c).minusSeconds(300)), node).stream().map[TeleportRequest](row => row.request).toList())

  @throws[Exception]
  def resolve(id: UUID, destination: Destination): Destination =
    db.transaction[Destination] { c =>
      val first = read(c, id); pair(c, first.request.requester(), first.request.recipient()); val row = read(c, id); active(c, row)
      if (row.request.state().equals("RESOLVED")) row.destination
      else {
        if (!row.request.state().equals("ACCEPTED")) throw new Rejected("수락되지 않았거나 이미 처리된 요청입니다.")
        val host = if (row.request.here()) row.request.requester() else row.request.recipient()
        Using.resource(sql(c, "SELECT node FROM link_sessions WHERE player=?", host)) { s =>
          Using.resource(s.executeQuery()) { r => r.next(); if (!destination.node().equals(r.getString(1))) throw new Rejected("목적지 서버가 변경되었습니다.") }
        }
        update(c, "UPDATE link_teleport_requests SET state='RESOLVED',destination_node=?,destination_world=?,x=?,y=?,z=?,yaw=?,pitch=? WHERE id=?", destination.node(), destination.world(), Double.box(destination.x()), Double.box(destination.y()), Double.box(destination.z()), Float.box(destination.yaw()), Float.box(destination.pitch()), id); journal(c, id); destination
      }
    }

  @throws[Exception]
  def dispatchable(id: UUID): Work =
    db.transaction[Work] { c => val row = read(c, id); active(c, row); if (!row.request.state().equals("RESOLVED")) throw new Rejected("처리할 티피 요청이 없습니다."); Work(row.request, row.destination) }

  @throws[Exception]
  def finish(id: UUID, submitted: Boolean): Unit =
    db.transaction[AnyRef] { c =>
      val first = read(c, id); pair(c, first.request.requester(), first.request.recipient()); val row = read(c, id)
      if (Set.of("ACCEPTED", "RESOLVED", "EXPIRED").contains(row.request.state())) {
        update(c, "UPDATE link_teleport_requests SET state=? WHERE id=?", if (submitted) "DISPATCHED" else "FAILED", id); journal(c, id)
      }
      null
    }
}

object TeleportStore {
  final class Rejected(message: String) extends IllegalArgumentException(message)
  final case class Work(request: TeleportRequest, destination: Destination)
  private final case class Row(request: TeleportRequest, fromEpoch: Long, toEpoch: Long, valid: Boolean, destination: Destination)

  private def username(value: String): Unit = { if (value == null || !value.matches("[A-Za-z0-9_]{1,16}")) throw new Rejected("올바른 플레이어 이름을 입력하세요.") }

  @throws[Exception]
  private def now(c: Connection): Instant =
    Using.resource(sql(c, "SELECT clock_timestamp()")) { s => Using.resource(s.executeQuery()) { r => r.next(); instant(r, 1) } }

  @throws[Exception]
  private def epoch(c: Connection, player: UUID): Long =
    Using.resource(sql(c, "SELECT s.epoch FROM link_sessions s JOIN link_nodes n ON n.id=s.node WHERE s.player=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp() AND n.heartbeat>clock_timestamp()-interval '15 seconds'", player)) { s =>
      Using.resource(s.executeQuery()) { r =>
        if (!r.next()) throw new Rejected("상대 또는 요청자가 접속 중이 아니거나 서버 이동 중입니다."); r.getLong(1)
      }
    }

  @throws[Exception]
  private def pair(c: Connection, a: UUID, b: UUID): Unit = {
    val it = List.of(a, b).stream().distinct().sorted().toList().iterator()
    while (it.hasNext) lock(c, "tpa-user:" + it.next())
  }

  @throws[Exception]
  private def active(c: Connection, row: Row): Unit = {
    if (!row.valid) throw new Rejected("티피 요청이 만료되었습니다.")
    if (epoch(c, row.request.requester()) != row.fromEpoch || epoch(c, row.request.recipient()) != row.toEpoch) throw new Rejected("재접속 또는 서버 이동으로 티피 요청이 무효화되었습니다.")
  }
}
