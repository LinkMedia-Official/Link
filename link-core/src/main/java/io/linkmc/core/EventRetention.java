package io.linkmc.core;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Bounded retention of historical events; current state and idempotency receipts are never deleted. */
public final class EventRetention {
    public record Result(int operations,int events,int notifications,int requests) {}
    private final Database db;
    public EventRetention(Database db){this.db=db;}
    public Result prune(int hours,int batch)throws Exception{
        if(hours<1||hours>87600||batch<1||batch>1000)throw new IllegalArgumentException("Invalid retention limits");
        return db.transaction(c->{
            Timestamp cutoff;
            try(var s=sql(c,"SELECT clock_timestamp()");var r=s.executeQuery()){
                r.next();cutoff=Timestamp.from(instant(r,1).minus(Duration.ofHours(hours)));
            }
            List<UUID> operations=new ArrayList<>();
            // Preserve complete transactions that still contain any aggregate's latest revision.
            // Concurrent commits only advance revisions, so a selected historical operation cannot become latest.
            try(var s=sql(c,"SELECT o.id FROM link_operations o WHERE NOT o.events_pruned AND o.created_at<? AND NOT EXISTS (SELECT 1 FROM link_events e JOIN link_state s ON s.id=e.aggregate_id AND s.version=e.version WHERE e.operation=o.id) ORDER BY o.created_at,o.id LIMIT ? FOR UPDATE SKIP LOCKED",cutoff,batch);var r=s.executeQuery()){
                while(r.next())operations.add(r.getObject(1,UUID.class));
            }
            int events=0;
            for(UUID operation:operations){
                events+=update(c,"DELETE FROM link_events WHERE operation=?",operation);
                update(c,"UPDATE link_operations SET events_pruned=true WHERE id=?",operation);
            }
            List<UUID> delivered=new ArrayList<>();
            try(var s=sql(c,"SELECT b.id FROM link_outbox b JOIN link_operations o ON o.id=b.id WHERE b.delivered AND o.created_at<? ORDER BY o.created_at,o.id LIMIT ? FOR UPDATE SKIP LOCKED",cutoff,batch);var r=s.executeQuery()){
                while(r.next())delivered.add(r.getObject(1,UUID.class));
            }
            for(UUID operation:delivered)update(c,"DELETE FROM link_outbox WHERE id=? AND delivered",operation);
            List<UUID> requests=new ArrayList<>();
            try(var s=sql(c,"SELECT id FROM link_teleport_requests WHERE expires_at<? ORDER BY expires_at,id LIMIT ? FOR UPDATE SKIP LOCKED",cutoff,batch);var r=s.executeQuery()){while(r.next())requests.add(r.getObject(1,UUID.class));}
            for(UUID request:requests)update(c,"DELETE FROM link_teleport_requests WHERE id=?",request);
            return new Result(operations.size(),events,delivered.size(),requests.size());
        });
    }
}
