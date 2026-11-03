package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Consent-bound requests. A request is bound to both login/session epochs. */
public final class TeleportStore {
    public static final class Rejected extends IllegalArgumentException {public Rejected(String message){super(message);}}
    public record Work(TeleportRequest request,Destination destination){}
    private record Row(TeleportRequest request,long fromEpoch,long toEpoch,boolean valid,Destination destination){}
    private final Database db;
    public TeleportStore(Database db){this.db=db;}
    public void registerPlayer(UUID player,String name)throws Exception{
        username(name);db.transaction(c->{update(c,"INSERT INTO link_player_names(player,name,lower_name) VALUES (?,?,?) ON CONFLICT(player) DO UPDATE SET name=excluded.name,lower_name=excluded.lower_name",player,name,name.toLowerCase(Locale.ROOT));return null;});
    }
    private static void username(String value){if(value==null||!value.matches("[A-Za-z0-9_]{1,16}"))throw new Rejected("올바른 플레이어 이름을 입력하세요.");}
    private static Instant now(Connection c)throws Exception{try(var s=sql(c,"SELECT clock_timestamp()");var r=s.executeQuery()){r.next();return instant(r,1);}}
    private static long epoch(Connection c,UUID player)throws Exception{
        try(var s=sql(c,"SELECT s.epoch FROM link_sessions s JOIN link_nodes n ON n.id=s.node WHERE s.player=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp() AND n.heartbeat>clock_timestamp()-interval '15 seconds'",player);var r=s.executeQuery()){
            if(!r.next())throw new Rejected("상대 또는 요청자가 접속 중이 아니거나 서버 이동 중입니다.");return r.getLong(1);
        }
    }
    private static void pair(Connection c,UUID a,UUID b)throws Exception{for(UUID id:List.of(a,b).stream().distinct().sorted().toList())lock(c,"tpa-user:"+id);}
    private UUID online(Connection c,String name)throws Exception{
        username(name);UUID result=null;
        try(var s=sql(c,"SELECT p.player FROM link_player_names p JOIN link_sessions s ON s.player=p.player JOIN link_nodes n ON n.id=s.node WHERE p.lower_name=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp() AND n.heartbeat>clock_timestamp()-interval '15 seconds'",name.toLowerCase(Locale.ROOT));var r=s.executeQuery()){
            while(r.next()){if(result!=null)throw new Rejected("동일한 이름의 접속 정보가 여러 개입니다.");result=r.getObject(1,UUID.class);}
        }
        if(result==null)throw new Rejected("접속 중인 플레이어를 찾을 수 없습니다: "+name);return result;
    }
    public List<String> onlinePlayers()throws Exception{
        return db.transaction(c->{List<String> names=new ArrayList<>();try(var s=sql(c,"SELECT p.name FROM link_player_names p JOIN link_sessions s ON s.player=p.player JOIN link_nodes n ON n.id=s.node WHERE s.state='ACTIVE' AND s.expires_at>clock_timestamp() AND n.heartbeat>clock_timestamp()-interval '15 seconds' ORDER BY p.lower_name");var r=s.executeQuery()){while(r.next())names.add(r.getString(1));}return List.copyOf(names);});
    }
    private List<Row> rows(Connection c,String where,Object... args)throws Exception{
        List<Row> result=new ArrayList<>();
        try(var s=sql(c,"SELECT q.*,a.name AS from_name,b.name AS to_name,q.expires_at>clock_timestamp() AS valid FROM link_teleport_requests q JOIN link_player_names a ON a.player=q.requester JOIN link_player_names b ON b.player=q.recipient WHERE "+where,args);var r=s.executeQuery()){
            while(r.next()){
                boolean valid=r.getBoolean("valid");String state=r.getString("state");if(!valid&&Set.of("PENDING","ACCEPTED","RESOLVED").contains(state))state="EXPIRED";
                var request=new TeleportRequest(r.getObject("id",UUID.class),r.getObject("requester",UUID.class),r.getString("from_name"),r.getObject("recipient",UUID.class),r.getString("to_name"),r.getBoolean("here"),state,instant(r,"expires_at"));
                Destination d=r.getString("destination_node")==null?null:new Destination(r.getString("destination_node"),r.getString("destination_world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"));
                result.add(new Row(request,r.getLong("requester_epoch"),r.getLong("recipient_epoch"),valid,d));
            }
        }return result;
    }
    private Row read(Connection c,UUID id)throws Exception{return rows(c,"q.id=?",id).stream().findFirst().orElseThrow(()->new Rejected("티피 요청을 찾을 수 없습니다."));}
    private static void active(Connection c,Row row)throws Exception{
        if(!row.valid())throw new Rejected("티피 요청이 만료되었습니다.");
        if(epoch(c,row.request().requester())!=row.fromEpoch()||epoch(c,row.request().recipient())!=row.toEpoch())throw new Rejected("재접속 또는 서버 이동으로 티피 요청이 무효화되었습니다.");
    }
    private void journal(Connection c,UUID id)throws Exception{
        Row row=read(c,id);String key="tpa:"+row.request().requester();
        long version=EventStore.read(c,key).map(State::version).orElse(0L);
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("id",id.toString());snapshot.put("requester",row.request().requester().toString());snapshot.put("requesterName",row.request().requesterName());
        snapshot.put("recipient",row.request().recipient().toString());snapshot.put("recipientName",row.request().recipientName());
        snapshot.put("requesterEpoch",row.fromEpoch());snapshot.put("recipientEpoch",row.toEpoch());snapshot.put("here",row.request().here());
        snapshot.put("state",row.request().state());snapshot.put("expiresAt",row.request().expiresAt().toString());snapshot.put("destination",row.destination());
        byte[] value=new com.google.gson.Gson().toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        new EventStore(db).commit(c,new Transaction(UUID.randomUUID(),List.of(),List.of(new Change(key,version,1,"teleport:"+row.request().state().toLowerCase(Locale.ROOT),value))));
    }
    public TeleportRequest request(UUID requester,String target,boolean here)throws Exception{
        return db.transaction(c->{UUID recipient=online(c,target);if(requester.equals(recipient))throw new Rejected("자신에게 티피 요청을 보낼 수 없습니다.");
            pair(c,requester,recipient);long from=epoch(c,requester),to=epoch(c,recipient);Instant time=now(c);
            try(var s=sql(c,"SELECT 1 FROM link_teleport_requests WHERE requester=? AND ((state IN ('PENDING','ACCEPTED','RESOLVED') AND expires_at>clock_timestamp()) OR created_at>?)",requester,Timestamp.from(time.minusSeconds(5)));var r=s.executeQuery()){
                if(r.next())throw new Rejected("진행 중인 요청이 있거나 재요청 대기 시간(5초)입니다. /티피취소로 대기 요청을 취소하세요.");
            }
            try(var s=sql(c,"SELECT count(*) FROM link_teleport_requests WHERE recipient=? AND state='PENDING' AND expires_at>clock_timestamp()",recipient);var r=s.executeQuery()){r.next();if(r.getInt(1)>=10)throw new Rejected("상대의 대기 요청이 너무 많습니다.");}
            UUID id=UUID.randomUUID();update(c,"INSERT INTO link_teleport_requests(id,requester,recipient,requester_epoch,recipient_epoch,here,state,expires_at) VALUES (?,?,?,?,?,?,'PENDING',?)",id,requester,recipient,from,to,here,Timestamp.from(time.plusSeconds(60)));
            journal(c,id);return read(c,id).request();
        });
    }
    public List<TeleportRequest> incoming(UUID recipient)throws Exception{
        return db.transaction(c->rows(c,"q.recipient=? AND q.state='PENDING' AND q.expires_at>clock_timestamp() ORDER BY q.created_at",recipient).stream().map(Row::request).toList());
    }
    public TeleportRequest respond(UUID recipient,String requesterName,boolean accept)throws Exception{
        return db.transaction(c->{
            var pending=rows(c,"q.recipient=? AND q.state='PENDING' AND q.expires_at>clock_timestamp() ORDER BY q.created_at",recipient);
            if(requesterName!=null){username(requesterName);pending=pending.stream().filter(r->r.request().requesterName().equalsIgnoreCase(requesterName)).toList();}
            if(pending.isEmpty())throw new Rejected("수락하거나 거절할 대기 요청이 없습니다.");
            if(pending.size()!=1)throw new Rejected("요청이 여러 개입니다. /티피수락 <요청자> 또는 /티피거절 <요청자>를 사용하세요.");
            var initial=pending.getFirst();pair(c,initial.request().requester(),recipient);var row=read(c,initial.request().id());
            if(!row.request().recipient().equals(recipient)||!row.request().state().equals("PENDING"))throw new Rejected("이미 처리된 티피 요청입니다.");
            active(c,row);update(c,"UPDATE link_teleport_requests SET state=? WHERE id=?",accept?"ACCEPTED":"DECLINED",row.request().id());journal(c,row.request().id());return read(c,row.request().id()).request();
        });
    }
    public boolean cancel(UUID requester)throws Exception{
        return db.transaction(c->{var pending=rows(c,"q.requester=? AND q.state='PENDING' AND q.expires_at>clock_timestamp()",requester);if(pending.isEmpty())return false;
            var initial=pending.getFirst();pair(c,requester,initial.request().recipient());var row=read(c,initial.request().id());if(!row.request().state().equals("PENDING"))return false;
            update(c,"UPDATE link_teleport_requests SET state='CANCELLED' WHERE id=?",row.request().id());journal(c,row.request().id());return true;
        });
    }
    public List<Work> work(String node)throws Exception{
        return db.transaction(c->rows(c,"q.state IN ('ACCEPTED','RESOLVED') AND q.expires_at>clock_timestamp() AND EXISTS (SELECT 1 FROM link_sessions s WHERE s.player=CASE WHEN q.here THEN q.requester ELSE q.recipient END AND s.node=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp()) ORDER BY q.created_at LIMIT 64",node).stream().map(r->new Work(r.request(),r.destination())).toList());
    }
    public List<TeleportRequest> notices(String node)throws Exception{
        return db.transaction(c->rows(c,"q.created_at>? AND ((q.state='PENDING' AND q.expires_at>clock_timestamp()) OR q.id=(SELECT newest.id FROM link_teleport_requests newest WHERE newest.requester=q.requester ORDER BY newest.created_at DESC,newest.id DESC LIMIT 1)) AND EXISTS (SELECT 1 FROM link_sessions s WHERE s.player IN (q.requester,q.recipient) AND s.node=? AND s.state='ACTIVE' AND s.expires_at>clock_timestamp()) ORDER BY q.created_at DESC",Timestamp.from(now(c).minusSeconds(300)),node).stream().map(Row::request).toList());
    }
    public Destination resolve(UUID id,Destination destination)throws Exception{
        return db.transaction(c->{var first=read(c,id);pair(c,first.request().requester(),first.request().recipient());var row=read(c,id);active(c,row);
            if(row.request().state().equals("RESOLVED"))return row.destination();
            if(!row.request().state().equals("ACCEPTED"))throw new Rejected("수락되지 않았거나 이미 처리된 요청입니다.");
            UUID host=row.request().here()?row.request().requester():row.request().recipient();
            try(var s=sql(c,"SELECT node FROM link_sessions WHERE player=?",host);var r=s.executeQuery()){r.next();if(!destination.node().equals(r.getString(1)))throw new Rejected("목적지 서버가 변경되었습니다.");}
            update(c,"UPDATE link_teleport_requests SET state='RESOLVED',destination_node=?,destination_world=?,x=?,y=?,z=?,yaw=?,pitch=? WHERE id=?",destination.node(),destination.world(),destination.x(),destination.y(),destination.z(),destination.yaw(),destination.pitch(),id);journal(c,id);return destination;
        });
    }
    public Work dispatchable(UUID id)throws Exception{return db.transaction(c->{var row=read(c,id);active(c,row);if(!row.request().state().equals("RESOLVED"))throw new Rejected("처리할 티피 요청이 없습니다.");return new Work(row.request(),row.destination());});}
    public void finish(UUID id,boolean submitted)throws Exception{
        db.transaction(c->{var first=read(c,id);pair(c,first.request().requester(),first.request().recipient());var row=read(c,id);
            if(!Set.of("ACCEPTED","RESOLVED","EXPIRED").contains(row.request().state()))return null;
            update(c,"UPDATE link_teleport_requests SET state=? WHERE id=?",submitted?"DISPATCHED":"FAILED",id);journal(c,id);return null;
        });
    }
}
