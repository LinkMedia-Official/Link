package io.linkmc.core;

import io.linkmc.api.LinkApi;
import io.linkmc.api.LinkApi.*;
import java.sql.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Durable group requests and node-bound creation jobs. All allocation decisions share the capacity lock. */
public final class WarpGroupStore {
    public record Job(String id,String group,String node,String world,String template,int version,int capacity){}
    public record Pending(UUID id,UUID player,String group,String node,long epoch,String state){}
    private final Database db;
    private final NetworkStore network;
    public WarpGroupStore(Database db){this.db=db;network=new NetworkStore(db);}
    private static WarpGroup group(ResultSet r)throws SQLException{return new WarpGroup(r.getString("name"),r.getString("template"),r.getInt("version"),r.getString("home_node"),new ChannelPolicy(r.getInt("capacity"),r.getInt("max_channels"),r.getBoolean("auto_expand"),r.getBoolean("network_wide")));}
    public List<WarpGroup> list()throws Exception{return db.transaction(c->{var result=new ArrayList<WarpGroup>();try(var s=sql(c,"SELECT * FROM link_warp_groups ORDER BY name");var r=s.executeQuery()){while(r.next())result.add(group(r));}return List.copyOf(result);});}
    public List<Channel> channels(String name)throws Exception{
        LinkApi.requireName(name);Set<String> ids=db.transaction(c->{Set<String> result=new HashSet<>();try(var s=sql(c,"SELECT id FROM link_group_worlds WHERE group_name=?",name);var r=s.executeQuery()){while(r.next())result.add(r.getString(1));}return result;});
        return network.channels().stream().filter(ch->ids.contains(ch.id())).toList();
    }
    public boolean exists(String name)throws Exception{return db.transaction(c->{try(var s=sql(c,"SELECT 1 FROM link_warp_groups WHERE name=?",name);var r=s.executeQuery()){return r.next();}});}
    public void bind(String name,String template,int version,String node)throws Exception{
        LinkApi.requireName(name);LinkApi.requireName(template);LinkApi.requireName(node);if(version<1)throw new IllegalArgumentException("Invalid template version");
        db.transaction(c->{lock(c,"warp:"+name);lock(c,"allocation");
            try(var s=sql(c,"SELECT 1 FROM link_templates WHERE name=? AND version=?",template,version);var r=s.executeQuery()){if(!r.next())throw new ConflictException("Publish the template first");}
            var policy=TemplateStore.policy(c,template,version);var desired=new WarpGroup(name,template,version,node,policy);
            try(var s=sql(c,"SELECT * FROM link_warp_groups WHERE name=?",name);var r=s.executeQuery()){if(r.next()){if(group(r).equals(desired))return null;throw new ConflictException("Existing group is immutable; use a new warp name");}}
            if(EventStore.read(c,LinkApi.warpStateId(name)).isPresent())throw new ConflictException("Previously used warp names cannot become a new group");
            try(var s=sql(c,"SELECT name FROM link_warps WHERE name=? UNION SELECT group_name FROM link_group_worlds WHERE group_name=?",name,name);var r=s.executeQuery()){if(r.next())throw new ConflictException("Warp name already in use or retired");}
            update(c,"INSERT INTO link_warp_groups(name,template,version,home_node,capacity,max_channels,auto_expand,network_wide) VALUES (?,?,?,?,?,?,?,?)",name,template,version,node,policy.capacity(),policy.maxChannels(),policy.autoExpand(),policy.networkWide());
            network.recordWarp(c,name,new com.google.gson.Gson().toJson(desired).getBytes(java.nio.charset.StandardCharsets.UTF_8),"warp:group");return null;
        });
    }
    public void enqueue(UUID player,UUID id,String name)throws Exception{
        LinkApi.requireName(name);db.transaction(c->{
            Session owner=SessionStore.get(c,player);SessionStore.requireActive(c,owner);lock(c,"allocation");
            try(var s=sql(c,"SELECT player,group_name FROM link_group_requests WHERE id=?",id);var r=s.executeQuery()){if(r.next()){if(!player.equals(r.getObject(1,UUID.class))||!name.equals(r.getString(2)))throw new ConflictException("Group request ID reused");return null;}}
            try(var s=sql(c,"SELECT 1 FROM link_warp_groups WHERE name=?",name);var r=s.executeQuery()){if(!r.next())throw new ConflictException("Unknown warp group");}
            try(var s=sql(c,"SELECT 1 FROM link_group_requests WHERE player=? AND state='WAITING' AND expires_at>clock_timestamp()",player);var r=s.executeQuery()){if(r.next())throw new ConflictException("Already waiting for a channel");}
            update(c,"INSERT INTO link_group_requests(id,player,group_name,node,epoch,state,expires_at) VALUES (?,?,?,?,?,'WAITING',clock_timestamp()+?*interval '1 second')",id,player,name,owner.node(),owner.epoch(),60);
            recordRequest(c,id);return null;
        });
    }
    private void recordRequest(Connection c,UUID id)throws Exception{
        try(var s=sql(c,"SELECT * FROM link_group_requests WHERE id=?",id);var r=s.executeQuery()){
            r.next();String key="group-request:"+r.getObject("player",UUID.class);String state=r.getString("state");
            var value=Map.of("id",id.toString(),"group",r.getString("group_name"),"node",r.getString("node"),"epoch",r.getLong("epoch"),"state",state,"expiresAt",instant(r,"expires_at").toString());
            long version=EventStore.read(c,key).map(State::version).orElse(0L);
            UUID operation=UUID.nameUUIDFromBytes(("group:"+id+":"+state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            new EventStore(db).commit(c,new Transaction(operation,List.of(),List.of(new Change(key,version,1,"group:"+state.toLowerCase(Locale.ROOT),new com.google.gson.Gson().toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        }
    }
    public List<Pending> pending(String node)throws Exception{return db.transaction(c->{var rows=new ArrayList<Pending>();try(var s=sql(c,"SELECT * FROM link_group_requests WHERE node=? AND state='WAITING' ORDER BY created_at LIMIT 64",node);var r=s.executeQuery()){while(r.next())rows.add(new Pending(r.getObject("id",UUID.class),r.getObject("player",UUID.class),r.getString("group_name"),r.getString("node"),r.getLong("epoch"),r.getString("state")));}return rows;});}
    /** Reservation, route, request transition and event commit together. Empty means waiting or rejected. */
    public Optional<RouteStore.Request> dispatch(Pending pending)throws Exception{
        return db.transaction(c->{
            lock(c,"session:"+pending.player());lock(c,"allocation");
            try(var s=sql(c,"SELECT q.state,q.expires_at>clock_timestamp() AS valid,s.state AS session_state,s.epoch,s.node, s.expires_at>clock_timestamp() AS session_valid FROM link_group_requests q LEFT JOIN link_sessions s ON s.player=q.player WHERE q.id=?",pending.id());var r=s.executeQuery()){
                if(!r.next()||!r.getString("state").equals("WAITING"))return Optional.empty();
                boolean active=r.getBoolean("valid")&&r.getBoolean("session_valid")&&"ACTIVE".equals(r.getString("session_state"))&&r.getLong("epoch")==pending.epoch()&&pending.node().equals(r.getString("node"));
                try(var check=sql(c,"SELECT 1 FROM link_warp_groups WHERE name=?",pending.group());var g=check.executeQuery()){active&=g.next();}
                if(!active){update(c,"UPDATE link_group_requests SET state='FAILED' WHERE id=?",pending.id());recordRequest(c,pending.id());return Optional.empty();}
            }
            Reservation reservation;
            try{reservation=network.reserveGroup(c,pending.player(),pending.id(),pending.group());}
            catch(ConflictException e){if(e.getMessage().startsWith("NO_CAPACITY")||e.getMessage().equals("Player already has an admission"))return Optional.empty();throw e;}
            update(c,"INSERT INTO link_routes(id,player,reservation,state) VALUES (?,?,?,'REQUESTED')",pending.id(),pending.player(),reservation.id());
            update(c,"UPDATE link_group_requests SET state='DISPATCHED' WHERE id=?",pending.id());recordRequest(c,pending.id());return Optional.of(new RouteStore.Request(pending.id(),reservation));
        });
    }
    public String status(UUID id)throws Exception{return db.transaction(c->{try(var s=sql(c,"SELECT state FROM link_group_requests WHERE id=?",id);var r=s.executeQuery()){return r.next()?r.getString(1):"MISSING";}});}
    /** At most one unfinished creation per group, under the global allocation lock. */
    public void provisionDemand()throws Exception{
        for(var group:list())db.transaction(c->{lock(c,"allocation");
            try(var s=sql(c,"SELECT 1 FROM link_warp_groups WHERE name=?",group.name());var r=s.executeQuery()){if(!r.next())return null;}
            try(var s=sql(c,"SELECT 1 FROM link_group_requests q JOIN link_sessions s ON s.player=q.player WHERE q.group_name=? AND q.state='WAITING' AND q.expires_at>clock_timestamp() AND s.state='ACTIVE' AND s.epoch=q.epoch AND s.expires_at>clock_timestamp()",group.name());var r=s.executeQuery()){if(!r.next())return null;}
            int count=0;try(var s=sql(c,"SELECT state FROM link_group_worlds WHERE group_name=?",group.name());var r=s.executeQuery()){while(r.next()){count++;if(r.getString(1).equals("CREATING"))return null;}}
            if(count>=(group.policy().autoExpand()?group.policy().maxChannels():1))return null;
            String room="SELECT 1 FROM link_channels c JOIN link_nodes n ON n.id=c.node JOIN link_group_worlds g ON g.id=c.id WHERE g.group_name=? AND g.state='READY' AND c.ready AND n.heartbeat>clock_timestamp()-interval '15 seconds' AND (SELECT count(DISTINCT r.player) FROM link_reservations r WHERE r.channel=c.id AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp()))<c.capacity AND (SELECT count(DISTINCT r.player) FROM link_reservations r JOIN link_channels nc ON nc.id=r.channel WHERE nc.node=c.node AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp()))<n.capacity";
            try(var s=sql(c,room,group.name());var r=s.executeQuery()){if(r.next())return null;}
            String query="SELECT n.id FROM link_nodes n WHERE n.heartbeat>clock_timestamp()-interval '15 seconds' AND (SELECT count(DISTINCT r.player) FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE c.node=n.id AND (r.state='ADMITTED' OR r.expires_at>clock_timestamp()))<n.capacity "+(group.policy().networkWide()?"":"AND n.id=? ")+"ORDER BY (SELECT count(*) FROM link_group_worlds w WHERE w.node=n.id),n.id LIMIT 1";
            String node;try(var s=group.policy().networkWide()?sql(c,query):sql(c,query,group.homeNode());var r=s.executeQuery()){if(!r.next())return null;node=r.getString(1);}
            String id="group-"+UUID.randomUUID();update(c,"INSERT INTO link_group_worlds(id,group_name,node,world,state) VALUES (?,?,?,?,'CREATING')",id,group.name(),node,id);
            journalWorld(c,id,"CREATING");return null;
        });
    }
    public List<Job> jobs(String node)throws Exception{return db.transaction(c->{var result=new ArrayList<Job>();try(var s=sql(c,"SELECT w.*,g.template,g.version,g.capacity FROM link_group_worlds w JOIN link_warp_groups g ON g.name=w.group_name WHERE w.node=? AND w.state='CREATING' ORDER BY w.id LIMIT 4",node);var r=s.executeQuery()){while(r.next())result.add(new Job(r.getString("id"),r.getString("group_name"),node,r.getString("world"),r.getString("template"),r.getInt("version"),r.getInt("capacity")));}return result;});}
    public void ready(Job job,Destination spawn,Session owner)throws Exception{
        if(!job.node().equals(spawn.node())||!job.world().equals(spawn.world())||!owner.node().equals(job.node()))throw new IllegalArgumentException("Wrong group destination");
        db.transaction(c->{SessionStore.requireActive(c,owner);lock(c,"allocation");
            try(var s=sql(c,"SELECT 1 FROM link_group_worlds w JOIN link_warp_groups g ON g.name=w.group_name WHERE w.id=? AND w.state='CREATING'",job.id());var r=s.executeQuery()){if(!r.next())return null;}
            update(c,"INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch,spawn_enabled) VALUES (?,?,?,?,true,?,?,?,?,?,false) ON CONFLICT(id) DO UPDATE SET ready=true",job.id(),job.node(),job.world(),job.capacity(),spawn.x(),spawn.y(),spawn.z(),spawn.yaw(),spawn.pitch());
            update(c,"UPDATE link_group_worlds SET state='READY' WHERE id=?",job.id());journalWorld(c,job.id(),"READY");return null;
        });
    }
    private void journalWorld(Connection c,String id,String state)throws Exception{
        String key="group-world:"+id;long version=EventStore.read(c,key).map(State::version).orElse(0L);
        try(var query=sql(c,"SELECT w.*,g.template,g.version,g.capacity FROM link_group_worlds w JOIN link_warp_groups g ON g.name=w.group_name WHERE w.id=?",id);var row=query.executeQuery()){
            row.next();var value=Map.of("id",id,"group",row.getString("group_name"),"node",row.getString("node"),"world",row.getString("world"),"template",row.getString("template"),"version",row.getInt("version"),"capacity",row.getInt("capacity"),"state",state);
            new EventStore(db).commit(c,new Transaction(UUID.randomUUID(),List.of(),List.of(new Change(key,version,1,"group-world:"+state.toLowerCase(Locale.ROOT),new com.google.gson.Gson().toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        }
    }
}
