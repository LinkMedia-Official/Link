package io.linkmc.core;

import io.linkmc.api.LinkApi;
import io.linkmc.api.LinkApi.*;
import java.sql.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** DB-clock leases and fencing. Redis locks are never ownership authority. */
public final class SessionStore {
    private final Database db;
    private final int leaseSeconds;
    public SessionStore(Database db, int leaseSeconds) {
        if (leaseSeconds < 5 || leaseSeconds > 300) throw new IllegalArgumentException("Lease must be 5..300 seconds");
        this.db = db; this.leaseSeconds = leaseSeconds;
    }
    public Session acquire(UUID player, String node) throws Exception {
        LinkApi.requireName(node);
        return db.transaction(c -> {sharedLock(c,"node-startup:"+node);return acquire(c,player,node);});
    }
    public static UUID nodeId(String node){LinkApi.requireName(node);return UUID.nameUUIDFromBytes(("link-node:"+node).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    /** Explicit startup takeover: latest durable state survives; old epochs cannot write or renew. */
    public Session acquireNode(String node)throws Exception{
        UUID id=nodeId(node);
        return db.transaction(c->{
            lock(c,"node-startup:"+node);
            List<UUID> owned=lockNodeSessions(c,node,id);
            try(var s=sql(c,"SELECT node FROM link_sessions WHERE player=?",id);var r=s.executeQuery()){
                if(r.next()&&!node.equals(r.getString(1)))throw new ConflictException("Node identity collision");
            }
            retireNode(c,node,id,owned);
            update(c,"INSERT INTO link_sessions(player,node,epoch,state,expires_at) VALUES (?,?,1,'ACTIVE',clock_timestamp()+?*interval '1 second') ON CONFLICT(player) DO UPDATE SET epoch=link_sessions.epoch+1,state='ACTIVE',expires_at=excluded.expires_at",id,node,leaseSeconds);
            return get(c,id);
        });
    }
    /** Idempotent cleanup; a stale process must never release its replacement's ownership. */
    public boolean releaseNode(Session owner)throws Exception{
        if(!owner.player().equals(nodeId(owner.node())))throw new IllegalArgumentException("Not a node lease");
        return db.exclusiveNodeTransaction(c->{
            lock(c,"node-startup:"+owner.node());
            List<UUID> owned=lockNodeSessions(c,owner.node(),owner.player());
            try(var s=sql(c,"SELECT node,epoch,state FROM link_sessions WHERE player=?",owner.player());var r=s.executeQuery()){
                if(!r.next()||!owner.node().equals(r.getString(1))||owner.epoch()!=r.getLong(2)||!r.getString(3).equals("ACTIVE"))return false;
            }
            retireNode(c,owner.node(),owner.player(),owned);
            update(c,"UPDATE link_sessions SET state='RELEASED',expires_at=clock_timestamp() WHERE player=? AND epoch=?",owner.player(),owner.epoch());
            return true;
        });
    }
    private static List<UUID> lockNodeSessions(Connection c,String node,UUID id)throws Exception{
        Set<UUID> ids=new HashSet<>();ids.add(id);
        try(var s=sql(c,"SELECT player FROM link_sessions WHERE node=?",node);var r=s.executeQuery()){while(r.next())ids.add(r.getObject(1,UUID.class));}
        List<UUID> ordered=ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        for(UUID player:ordered)lock(c,"session:"+player);
        return ordered;
    }
    private static void retireNode(Connection c,String node,UUID nodeId,List<UUID> owned)throws Exception{
        lock(c,"allocation");
        for(UUID player:owned){
            if(player.equals(nodeId))continue;
            int retired=update(c,"UPDATE link_sessions SET epoch=epoch+1,state='RELEASED',expires_at=clock_timestamp() WHERE player=? AND node=? AND state IN ('ACTIVE','MOVING')",player,node);
            if(retired==0)continue;
            update(c,"UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'",player);
            update(c,"UPDATE link_routes SET state='FAILED' WHERE player=? AND state IN ('REQUESTED','READY','CONNECTING')",player);
            update(c,"UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED'",player);
            update(c,"DELETE FROM link_reservations WHERE player=?",player);
        }
        update(c,"UPDATE link_channels SET ready=false WHERE node=?",node);
        update(c,"UPDATE link_arrivals SET state='CANCELLED' WHERE node=? AND state='ISSUED'",node);
        update(c,"UPDATE link_routes SET state='FAILED' WHERE state IN ('REQUESTED','READY','CONNECTING') AND reservation IN (SELECT r.id FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE c.node=?)",node);
        update(c,"DELETE FROM link_reservations r USING link_channels c WHERE r.channel=c.id AND c.node=?",node);
    }
    Session acquire(java.sql.Connection c, UUID player, String node) throws Exception {
            lock(c,"session:" + player);
            try (var s = sql(c,"SELECT state,expires_at>clock_timestamp() FROM link_sessions WHERE player=? FOR UPDATE",player); var r=s.executeQuery()) {
                if(r.next() && r.getBoolean(2) && (r.getString(1).equals("MOVING") || r.getString(1).equals("ACTIVE")))
                    throw new ConflictException("Player already owned or transferring");
            }
            // An expired owner can never publish another frame. Invalidate its unclaimed
            // transfer before issuing a new epoch, so delayed destination connections fail.
            update(c,"UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'",player);
            update(c,"UPDATE link_routes SET state='FAILED' WHERE player=? AND state='REQUESTED'",player);
            update(c,"INSERT INTO link_sessions(player,node,epoch,state,expires_at) VALUES (?,?,1,'ACTIVE',clock_timestamp()+?*interval '1 second') ON CONFLICT(player) DO UPDATE SET node=excluded.node,epoch=link_sessions.epoch+1,state='ACTIVE',expires_at=excluded.expires_at",player,node,leaseSeconds);
            return get(c,player);

    }
    public Session renew(Session session) throws Exception {
        // Renewal changes only expiry. Do not wait for the session advisory lock held
        // throughout a checkpoint; ownership changes remain serialized by that lock.
        for(int attempt=0;;attempt++){
            try{return db.transaction(c -> {
                // Check expiry only after obtaining the row: time may advance while waiting.
                try(var query=sql(c,"SELECT player FROM link_sessions WHERE player=? FOR UPDATE",session.player());var rows=query.executeQuery()){
                    if(!rows.next())throw new ConflictException("Missing session");
                }
                int changed=update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()+?*interval '1 second' WHERE player=? AND node=? AND epoch=? AND state='ACTIVE' AND expires_at>clock_timestamp()",leaseSeconds,session.player(),session.node(),session.epoch());
                if(changed!=1)throw new ConflictException("Expired, transferred or fenced session");
                return get(c,session.player());
            });}catch(SQLException error){
                String state=error.getSQLState();
                boolean transientFailure=state!=null&&(state.equals("55P03")||state.equals("40001")||state.equals("40P01")||state.startsWith("08")||error.getErrorCode()==1205);
                if(!transientFailure||attempt>=2)throw error;
                Thread.sleep(200L*(attempt+1));
            }
        }
    }
    public void release(Session session) throws Exception {
        db.transaction(c -> {
            requireActive(c,session);
            update(c,"UPDATE link_sessions SET state='RELEASED',expires_at=clock_timestamp() WHERE player=?",session.player());
            return null;
        });
    }
    static void requireActive(Connection c, Session session) throws SQLException {
        lock(c,"session:" + session.player());
        try(var s=sql(c,"SELECT node,epoch,state,expires_at>clock_timestamp() FROM link_sessions WHERE player=?",session.player()); var r=s.executeQuery()) {
            if(!r.next() || !r.getString(1).equals(session.node()) || r.getLong(2)!=session.epoch()
                    || !r.getString(3).equals("ACTIVE") || !r.getBoolean(4))
                throw new ConflictException("Expired, transferred or fenced session");
        }
    }
    static Session get(Connection c, UUID player) throws SQLException {
        try(var s=sql(c,"SELECT node,epoch,expires_at FROM link_sessions WHERE player=?",player); var r=s.executeQuery()) {
            if(!r.next()) throw new ConflictException("Missing session");
            return new Session(player,r.getString(1),r.getLong(2),instant(r,3));
        }
    }
    public Transfer prepare(Session source, UUID operation, Destination dest) throws Exception {
        return db.transaction(c -> prepare(c,source,operation,dest));
    }
    Transfer prepare(java.sql.Connection c,Session source,UUID operation,Destination dest)throws Exception{
            lock(c,"session:" + source.player());
            try(var s=sql(c,"SELECT * FROM link_transfers WHERE id=?",operation); var r=s.executeQuery()) {
                if(r.next()) {
                    if(!r.getObject("player",UUID.class).equals(source.player()) || r.getLong("source_epoch")!=source.epoch()
                            || !r.getString("source_node").equals(source.node()) || !destination(r).equals(dest))
                        throw new ConflictException("Transfer ID reused");
                    return new Transfer(operation,source.player(),r.getLong("final_version"),dest);
                }
            }
            requireActive(c,source);
            // Player writers take the same session lock before the aggregate lock.
            long version=EventStore.read(c,"player:"+source.player()).map(State::version).orElse(0L);
            update(c,"INSERT INTO link_transfers(id,player,source_node,source_epoch,destination_node,world,x,y,z,yaw,pitch,final_version,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED')",
                    operation,source.player(),source.node(),source.epoch(),dest.node(),dest.world(),dest.x(),dest.y(),dest.z(),dest.yaw(),dest.pitch(),version);
            update(c,"UPDATE link_sessions SET state='MOVING' WHERE player=?",source.player());
            return new Transfer(operation,source.player(),version,dest);
    }
    /** Release only this connection's epoch. A departed source cannot release an accepted destination. */
    public boolean disconnect(Session owner)throws Exception{
        return db.transaction(c->{
            lock(c,"session:"+owner.player());
            try(var q=sql(c,"SELECT node,epoch,state FROM link_sessions WHERE player=?",owner.player());var r=q.executeQuery()){
                if(!r.next()||!r.getString(1).equals(owner.node())||r.getLong(2)!=owner.epoch()||!Set.of("ACTIVE","MOVING").contains(r.getString(3)))return false;
            }
            lock(c,"allocation");
            update(c,"UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'",owner.player());
            update(c,"UPDATE link_routes SET state='FAILED' WHERE player=? AND state IN ('REQUESTED','READY','CONNECTING')",owner.player());
            update(c,"UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED'",owner.player());
            update(c,"DELETE FROM link_reservations WHERE player=?",owner.player());
            update(c,"UPDATE link_sessions SET state='RELEASED',expires_at=clock_timestamp() WHERE player=?",owner.player());
            return true;
        });
    }
    /** Source can extend a frozen handoff; accepted/replaced epochs are never touched. */
    public boolean renewMoving(Session owner)throws Exception{
        return db.transaction(c->update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()+?*interval '1 second' WHERE player=? AND node=? AND epoch=? AND state='MOVING' AND expires_at>clock_timestamp()",leaseSeconds,owner.player(),owner.node(),owner.epoch())==1);
    }
    public boolean available(UUID player)throws Exception{
        return db.transaction(c->{
            try(var q=sql(c,"SELECT state,expires_at>clock_timestamp() FROM link_sessions WHERE player=?",player);var r=q.executeQuery()){
                if(!r.next()||!Set.of("ACTIVE","MOVING").contains(r.getString(1)))return true;
                if(r.getBoolean(2))return false;
            }
            lock(c,"session:"+player);
            try(var q=sql(c,"SELECT state,expires_at>clock_timestamp() FROM link_sessions WHERE player=?",player);var r=q.executeQuery()){
                if(!r.next()||!Set.of("ACTIVE","MOVING").contains(r.getString(1)))return true;
                if(r.getBoolean(2))return false;
            }
            // A crashed source may leave a checkpoint-pinned reservation. Its expired epoch cannot write again.
            lock(c,"allocation");
            update(c,"UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'",player);
            update(c,"UPDATE link_routes SET state='FAILED' WHERE player=? AND state IN ('REQUESTED','READY','CONNECTING')",player);
            update(c,"UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED'",player);
            update(c,"DELETE FROM link_reservations WHERE player=?",player);
            update(c,"UPDATE link_sessions SET state='RELEASED' WHERE player=?",player);return true;
        });
    }
    public Session accept(UUID player, UUID transfer, String node) throws Exception {
        return db.transaction(c -> {sharedLock(c,"node-startup:"+node);return accept(c,player,transfer,node);});
    }
    Session accept(java.sql.Connection c, UUID player, UUID transfer, String node) throws Exception {
            lock(c,"session:" + player);
            try(var s=sql(c,"SELECT * FROM link_transfers WHERE id=? AND player=? FOR UPDATE",transfer,player); var r=s.executeQuery()) {
                if(!r.next() || !r.getString("destination_node").equals(node)) throw new ConflictException("Wrong transfer destination");
                if(r.getString("state").equals("ACCEPTED")) {
                    Session current=get(c,player);
                    if(current.epoch()!=r.getLong("accepted_epoch") || !current.node().equals(node)) throw new ConflictException("Transfer already superseded");
                    requireActive(c,current);
                    return current;
                }
                if(!r.getString("state").equals("PREPARED"))throw new ConflictException("Transfer cancelled");
                int changed=update(c,"UPDATE link_sessions SET node=?,epoch=epoch+1,state='ACTIVE',expires_at=clock_timestamp()+?*interval '1 second' WHERE player=? AND state='MOVING' AND epoch=? AND node=? AND expires_at>clock_timestamp()",
                        node,leaseSeconds,player,r.getLong("source_epoch"),r.getString("source_node"));
                if(changed!=1) throw new ConflictException("Transfer ownership mismatch");
                Session current=get(c,player);
                update(c,"UPDATE link_transfers SET state='ACCEPTED',accepted_epoch=? WHERE id=?",current.epoch(),transfer);
                return current;
            }

    }
    static Destination destination(ResultSet r) throws SQLException {
        return new Destination(r.getString("destination_node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"));
    }
}
