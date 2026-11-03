package io.linkmc.core;

import io.linkmc.api.LinkApi;
import io.linkmc.api.LinkApi.*;
import java.sql.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** DB reservations, not Redis counters, enforce both node and world capacities. */
public final class NetworkStore {
    private final Database db;
    public NetworkStore(Database db) { this.db=db; }
    public void heartbeat(String node, int capacity) throws Exception {
        LinkApi.requireName(node);
        if(capacity<1) throw new IllegalArgumentException("Invalid capacity");
        db.transaction(c -> {
            lock(c,"allocation");
            update(c,"INSERT INTO link_nodes(id,capacity) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET capacity=excluded.capacity,heartbeat=clock_timestamp()",node,capacity);
            return null;
        });
    }
    public void register(String id, Destination spawn, int capacity) throws Exception {
        LinkApi.requireName(id);
        if(capacity<1) throw new IllegalArgumentException("Invalid capacity");
        db.transaction(c -> {
            lock(c,"allocation");
            try(var check=sql(c,"SELECT node,world FROM link_channels WHERE id=?",id);var row=check.executeQuery()){
                if(row.next()&&(!row.getString(1).equals(spawn.node())||!row.getString(2).equals(spawn.world())))throw new ConflictException("Channel ID belongs to another world");
            }
            String channel=id;
            try(var check=sql(c,"SELECT id FROM link_channels WHERE node=? AND world=?",spawn.node(),spawn.world());var row=check.executeQuery()){
                if(row.next())channel=row.getString(1);
            }
            update(c,"INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch) VALUES (?,?,?,?,true,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET capacity=excluded.capacity,ready=true,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch",
                    channel,spawn.node(),spawn.world(),capacity,spawn.x(),spawn.y(),spawn.z(),spawn.yaw(),spawn.pitch());

            return null;
        });
    }
    public void ready(String id, boolean ready) throws Exception {
        db.transaction(c -> { lock(c,"allocation"); update(c,"UPDATE link_channels SET ready=? WHERE id=?",ready,id); return null; });
    }
    public void reconcileNode(Session nodeLease)throws Exception{
        db.transaction(c->{
            SessionStore.requireActive(c,nodeLease);lock(c,"allocation");
            update(c,"UPDATE link_channels SET ready=false WHERE node=?",nodeLease.node());
            update(c,"DELETE FROM link_reservations r USING link_channels c WHERE r.channel=c.id AND c.node=? AND (r.expires_at<=clock_timestamp() OR NOT EXISTS(SELECT 1 FROM link_sessions s WHERE s.player=r.player AND s.state IN ('ACTIVE','MOVING') AND s.expires_at>clock_timestamp()))",nodeLease.node());
            return null;
        });
    }
    public List<Channel> channels() throws Exception {
        return channels(false);
    }
    public List<Channel> spawnChannels() throws Exception {return channels(true);}
    private List<Channel> channels(boolean spawnOnly) throws Exception {
        return db.transaction(c -> {
            List<Channel> result=new ArrayList<>();
            try(var s=sql(c,"SELECT c.id,c.node,c.world,c.capacity,count(r.id) FILTER(WHERE r.state='ADMITTED') AS occupied,count(DISTINCT r.player)-count(DISTINCT r.player) FILTER(WHERE r.state='ADMITTED') AS reserved FROM link_channels c JOIN link_nodes n ON n.id=c.node LEFT JOIN link_reservations r ON r.channel=c.id AND r.expires_at>clock_timestamp() WHERE c.ready AND "+(spawnOnly?"c.spawn_enabled AND ":"")+"n.heartbeat>clock_timestamp()-interval '15 seconds' GROUP BY c.id,c.node,c.world,c.capacity ORDER BY c.id"); var r=s.executeQuery()) {
                while(r.next()) result.add(new Channel(r.getString("id"),r.getString("node"),r.getString("world"),r.getInt("capacity"),r.getInt("occupied"),r.getInt("reserved")));
            }
            return List.copyOf(result);
        });
    }
    public Reservation reserve(UUID player, UUID request) throws Exception {
        return reserve(player,request,null);
    }
    public Reservation reserve(UUID player, UUID request, Destination target) throws Exception {
        return db.transaction(c -> {
            lock(c,"allocation");
            // Idempotent retries return the same destination; no extra slot is consumed.
            try(var s=sql(c,"SELECT r.*,c.node,c.world,c.x,c.y,c.z,c.yaw,c.pitch,r.expires_at>clock_timestamp() AS valid FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE r.id=?",request); var r=s.executeQuery()) {
                if(r.next()) {
                    if(!player.equals(r.getObject("player",UUID.class))) throw new ConflictException("Reservation ID reused");
                    if(!r.getString("state").equals("RESERVED")) throw new ConflictException("Already admitted");
                    if(!r.getBoolean("valid")) throw new ConflictException("Reservation expired");
                    if(target!=null&&!reservation(r).destination().equals(target))throw new ConflictException("Reservation destination changed");
                    return reservation(r);
                }
            }
            // Expired admitted sessions are NOT silently freed: the backend must
            // stop simulation before releasing its admission. Dead nodes are excluded.
            update(c,"DELETE FROM link_reservations WHERE state='RESERVED' AND expires_at<=clock_timestamp()");
            try(var s=sql(c,"SELECT 1 FROM link_reservations WHERE player=? AND state='RESERVED'",player); var r=s.executeQuery()) {
                if(r.next()) throw new ConflictException("Player already has an admission");
            }
            String query="SELECT c.* FROM link_channels c JOIN link_nodes n ON n.id=c.node WHERE c.ready AND n.heartbeat>clock_timestamp()-interval '15 seconds' AND (SELECT count(DISTINCT r.player) FROM link_reservations r WHERE r.channel=c.id AND r.player<>?)<c.capacity AND (SELECT count(DISTINCT r.player) FROM link_reservations r JOIN link_channels nc ON nc.id=r.channel WHERE nc.node=c.node AND r.player<>?)<n.capacity ORDER BY (SELECT count(*) FROM link_reservations r WHERE r.channel=c.id) DESC,c.id LIMIT 1";
            if(target!=null)query=query.replace("WHERE c.ready AND", "WHERE c.node=? AND c.world=? AND c.ready AND");
            else query=query.replace("WHERE c.ready AND", "WHERE c.spawn_enabled AND c.ready AND");
            // Existing players prefer their current backend; first admission still balances normally.
            if(target==null)query=query.replace("ORDER BY (SELECT", "ORDER BY CASE WHEN c.node=(SELECT node FROM link_sessions WHERE player=? AND state='ACTIVE' AND expires_at>clock_timestamp()) THEN 0 ELSE 1 END, (SELECT");
            try(var s=target==null?sql(c,query,player,player,player):sql(c,query,target.node(),target.world(),player,player); var r=s.executeQuery()) {
                if(!r.next()) throw new ConflictException("NO_CAPACITY: provision a ready spawn before retrying");
                String channel=r.getString("id");
                Destination destination=target!=null?target:new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"));
                try(var insert=sql(c,"INSERT INTO link_reservations(id,player,channel,state,expires_at,x,y,z,yaw,pitch) VALUES (?,?,?,'RESERVED',clock_timestamp()+interval '30 seconds',?,?,?,?,?) RETURNING expires_at",request,player,channel,destination.x(),destination.y(),destination.z(),destination.yaw(),destination.pitch()); var expiry=insert.executeQuery()) {
                    expiry.next(); return new Reservation(request,player,channel,destination,instant(expiry,1));
                }
            }
        });
    }
    public Destination admit(UUID reservation, Session owner) throws Exception {
        return db.transaction(c -> admit(c,reservation,owner));
    }
    Destination admit(java.sql.Connection c, UUID reservation, Session owner) throws Exception {
            // Match session -> allocation lock order everywhere.
            SessionStore.requireActive(c,owner); lock(c,"allocation");
            try(var s=sql(c,"SELECT r.*,c.node,c.world,c.x,c.y,c.z,c.yaw,c.pitch,c.ready,n.heartbeat>clock_timestamp()-interval '15 seconds' AS alive,r.expires_at>clock_timestamp() AS valid FROM link_reservations r JOIN link_channels c ON c.id=r.channel JOIN link_nodes n ON n.id=c.node WHERE r.id=? AND r.player=? FOR UPDATE OF r",reservation,owner.player()); var r=s.executeQuery()) {
                if(!r.next() || !r.getString("node").equals(owner.node()) || !r.getBoolean("ready") || !r.getBoolean("alive") || !r.getBoolean("valid"))
                    throw new ConflictException("Invalid destination reservation");
                // The target already owns the fenced session. Only now release
                // its previous admission, in the same transaction as target entry.
                update(c,"DELETE FROM link_reservations WHERE player=? AND state='ADMITTED' AND id<>?",owner.player(),reservation);
                update(c,"UPDATE link_reservations SET state='ADMITTED',expires_at='infinity' WHERE id=?",reservation);
                return reservation(r).destination();
            }

    }
    public void cancel(UUID id, UUID player) throws Exception {
        db.transaction(c -> { lock(c,"allocation"); update(c,"DELETE FROM link_reservations WHERE id=? AND player=? AND state='RESERVED'",id,player); return null; });
    }
    public void depart(Session owner) throws Exception {
        db.transaction(c -> {
            SessionStore.requireActive(c,owner); lock(c,"allocation");
            update(c,"DELETE FROM link_reservations r USING link_channels c WHERE r.channel=c.id AND r.player=? AND c.node=?",owner.player(),owner.node()); return null;
        });
    }
    public int channelCount(String node) throws Exception {
        return db.transaction(c -> {try(var s=sql(c,"SELECT count(*) FROM link_channels WHERE node=? AND spawn_enabled",node);var r=s.executeQuery()){r.next();return r.getInt(1);}});
    }
    public Optional<Destination> warp(String name) throws Exception {
        LinkApi.requireName(name);
        return db.transaction(c -> {
            try(var s=sql(c,"SELECT * FROM link_warps WHERE name=?",name); var r=s.executeQuery()) {
                return r.next()?Optional.of(new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"))):Optional.empty();
            }
        });
    }
    public void setWarp(String name, Destination d) throws Exception {
        setWarp(name,d,0);
    }
    public void setWarp(String name,Destination d,int capacity)throws Exception{
        LinkApi.requireName(name);
        if(capacity<0)throw new IllegalArgumentException("Invalid capacity");
        db.transaction(c->{
            lock(c,"warp:"+name);
            if(capacity>0){
                lock(c,"allocation");
                boolean exists;
                try(var s=sql(c,"SELECT 1 FROM link_channels WHERE node=? AND world=?",d.node(),d.world());var r=s.executeQuery()){exists=r.next();}
                if(!exists){
                    String id="warp-"+UUID.randomUUID();
                    update(c,"INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch,spawn_enabled) VALUES (?,?,?,?,true,?,?,?,?,?,false)",id,d.node(),d.world(),capacity,d.x(),d.y(),d.z(),d.yaw(),d.pitch());
                }
            }
            recordWarp(c,name,new com.google.gson.Gson().toJson(d).getBytes(java.nio.charset.StandardCharsets.UTF_8),"warp:set");
            update(c,"INSERT INTO link_warps(name,node,world,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET node=excluded.node,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch",name,d.node(),d.world(),d.x(),d.y(),d.z(),d.yaw(),d.pitch());return null;
        });
    }
    private void recordWarp(Connection c,String name,byte[] value,String type)throws Exception{
        String id=LinkApi.warpStateId(name);long version=EventStore.read(c,id).map(State::version).orElse(0L);
        new EventStore(db).commit(c,new Transaction(UUID.randomUUID(),List.of(),List.of(new Change(id,version,1,type,value))));
    }
    public boolean deleteWarp(String name)throws Exception{
        LinkApi.requireName(name);
        return db.transaction(c->{
            lock(c,"warp:"+name);
            if(update(c,"DELETE FROM link_warps WHERE name=?",name)==0)return false;
            recordWarp(c,name,new byte[0],"warp:delete");return true;
        });
    }
    public List<String> warps()throws Exception{
        return db.transaction(c->{List<String> names=new ArrayList<>();try(var s=sql(c,"SELECT name FROM link_warps ORDER BY name");var r=s.executeQuery()){while(r.next())names.add(r.getString(1));}return List.copyOf(names);});
    }
    public void syncWarpWorlds(String node,Collection<String> loaded)throws Exception{
        db.transaction(c->{lock(c,"allocation");try(var s=sql(c,"SELECT id,world,ready FROM link_channels WHERE node=? AND NOT spawn_enabled",node);var r=s.executeQuery()){
            while(r.next()){boolean ready=loaded.contains(r.getString(2));if(ready!=r.getBoolean(3))update(c,"UPDATE link_channels SET ready=? WHERE id=?",ready,r.getString(1));}
        }return null;});
    }
    public void closeWorld(String node,String world)throws Exception{
        db.transaction(c->{lock(c,"allocation");
            try(var s=sql(c,"SELECT 1 FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE c.node=? AND c.world=? AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp())",node,world);var r=s.executeQuery()){
                if(r.next())throw new ConflictException("World still has admissions or pending travel");
            }
            update(c,"UPDATE link_channels SET ready=false WHERE node=? AND world=?",node,world);return null;
        });
    }
    public void openWarpWorld(String node,String world)throws Exception{
        db.transaction(c->{lock(c,"allocation");update(c,"UPDATE link_channels SET ready=true WHERE node=? AND world=? AND NOT spawn_enabled",node,world);return null;});
    }
    public void prepareDestination(Destination d,int capacity)throws Exception{
        if(capacity<1)throw new IllegalArgumentException("Invalid capacity");
        db.transaction(c->{lock(c,"allocation");
            try(var s=sql(c,"SELECT 1 FROM link_channels WHERE node=? AND world=?",d.node(),d.world());var r=s.executeQuery()){if(r.next())return null;}
            update(c,"INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch,spawn_enabled) VALUES (?,?,?,?,true,?,?,?,?,?,false)","warp-"+UUID.randomUUID(),d.node(),d.world(),capacity,d.x(),d.y(),d.z(),d.yaw(),d.pitch());return null;
        });
    }
    private static Reservation reservation(ResultSet r) throws SQLException {
        return new Reservation(r.getObject("id",UUID.class),r.getObject("player",UUID.class),r.getString("channel"),new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch")),instant(r,"expires_at"));
    }
}
