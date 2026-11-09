package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Backend commands or plugins request travel; only the source backend may freeze/checkpoint its player. */
public final class RouteStore {
    public record Request(UUID id,Reservation reservation){}
    private final Database db;
    public RouteStore(Database db){this.db=db;}
    private record Receipt(Session owner,Request request,boolean claimed){}
    public Reservation requestTravel(UUID player,UUID id,Destination destination)throws Exception{
        return db.transaction(c->{
            lock(c,"session:"+player);
            var prior=receipt(c,id);
            if(prior.isPresent()){
                Reservation reservation=prior.get().request().reservation();
                if(!reservation.player().equals(player)||(destination!=null&&!destination.equals(reservation.destination())))throw new ConflictException("Route ID reused");
                return reservation;
            }
            Reservation reservation=new NetworkStore(db).reserve(c,player,id,destination,null);
            request(c,id,reservation);return reservation;
        });
    }
    public void request(UUID id,Reservation reservation)throws Exception{
        db.transaction(c->{request(c,id,reservation);return null;});
    }
    private void request(java.sql.Connection c,UUID id,Reservation reservation)throws Exception{
        lock(c,"session:"+reservation.player());
        var prior=receipt(c,id);
        if(prior.isPresent()){
            if(!prior.get().request().reservation().equals(reservation))throw new ConflictException("Route ID reused");
            return;
        }
        Session owner=SessionStore.get(c,reservation.player());SessionStore.requireActive(c,owner);
        try(var q=sql(c,"SELECT player,reservation FROM link_routes WHERE id=?",id);var r=q.executeQuery()){
            if(r.next()){
                if(!r.getObject(1,UUID.class).equals(reservation.player())||!r.getObject(2,UUID.class).equals(reservation.id()))throw new ConflictException("Route ID reused");
            }else update(c,"INSERT INTO link_routes(id,player,reservation,state) VALUES (?,?,?,'REQUESTED')",id,reservation.player(),reservation.id());
        }
        update(c,"INSERT INTO link_route_receipts(id,player,node,epoch,details) VALUES (?,?,?,?,?)",id,owner.player(),owner.node(),owner.epoch(),encode(reservation));
    }
    /** Durable claim survives polling, callbacks and a changed destination session epoch. */
    public boolean claim(Request request,Session owner)throws Exception{
        return db.transaction(c->{
            lock(c,"session:"+owner.player());
            if(!status(c,request.id()).equals("REQUESTED"))return false;
            var prior=receipt(c,request.id());
            if(prior.isEmpty()){request(c,request.id(),request.reservation());prior=receipt(c,request.id());}
            Receipt receipt=prior.orElseThrow();
            if(!receipt.request().equals(request))throw new ConflictException("Route payload changed");
            if(receipt.claimed())return false;
            requireOwner(receipt,owner);SessionStore.requireActive(c,owner);
            try(var q=sql(c,"SELECT 1 FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE r.id=? AND r.player=? AND r.state='RESERVED' AND r.expires_at>clock_timestamp() AND c.ready AND c.node=?",request.reservation().id(),owner.player(),request.reservation().destination().node());var r=q.executeQuery()){
                if(!r.next())throw new ConflictException("Travel reservation expired or destination unavailable");
            }
            // Claimed work may wait for a large checkpoint. Crash/failed-route cleanup releases it.
            update(c,"UPDATE link_reservations SET expires_at='infinity' WHERE id=?",request.reservation().id());
            update(c,"UPDATE link_route_receipts SET claimed=true WHERE id=?",request.id());return true;
        });
    }
    public Destination completeLocal(Request request,Session owner)throws Exception{
        return db.transaction(c->{
            lock(c,"session:"+owner.player());Receipt receipt=receipt(c,request.id()).orElseThrow();
            requireOwner(receipt,owner);
            if(!receipt.request().equals(request)||!receipt.claimed()||!owner.node().equals(request.reservation().destination().node()))throw new ConflictException("Invalid local route");
            if(status(c,request.id()).equals("COMPLETE"))return request.reservation().destination();
            if(!status(c,request.id()).equals("REQUESTED"))throw new ConflictException("Local route no longer pending");
            Destination result=new NetworkStore(db).admit(c,request.reservation().id(),owner);
            update(c,"UPDATE link_routes SET state='COMPLETE' WHERE id=?",request.id());return result;
        });
    }
    public void prepareRemote(Request request,Session owner)throws Exception{
        db.transaction(c->{
            lock(c,"session:"+owner.player());Receipt receipt=receipt(c,request.id()).orElseThrow();requireOwner(receipt,owner);
            if(!receipt.request().equals(request)||!receipt.claimed()||owner.node().equals(request.reservation().destination().node()))throw new ConflictException("Invalid remote route");
            if(!status(c,request.id()).equals("REQUESTED"))return null;
            new SessionStore(db,30).prepare(c,owner,request.id(),request.reservation().destination());
            new ArrivalStore(db).issue(c,request.id(),request.reservation(),request.id());return null;
        });
    }
    public void reject(Request request)throws Exception{
        db.transaction(c->{lock(c,"session:"+request.reservation().player());lock(c,"allocation");
            if(update(c,"UPDATE link_routes SET state='FAILED' WHERE id=? AND player=? AND state='REQUESTED'",request.id(),request.reservation().player())==1)
                update(c,"DELETE FROM link_reservations WHERE id=? AND player=? AND state='RESERVED'",request.reservation().id(),request.reservation().player());
            return null;
        });
    }
    private static void requireOwner(Receipt receipt,Session owner){
        if(!receipt.owner().player().equals(owner.player())||!receipt.owner().node().equals(owner.node())||receipt.owner().epoch()!=owner.epoch())throw new ConflictException("Route belongs to an earlier player session");
    }
    private static String status(java.sql.Connection c,UUID id)throws Exception{
        try(var q=sql(c,"SELECT state FROM link_routes WHERE id=?",id);var r=q.executeQuery()){return r.next()?r.getString(1):"MISSING";}
    }
    private static Optional<Receipt> receipt(java.sql.Connection c,UUID id)throws Exception{
        try(var q=sql(c,"SELECT * FROM link_route_receipts WHERE id=?",id);var r=q.executeQuery()){
            if(!r.next())return Optional.empty();Reservation reservation=decode(r.getBytes("details"));
            return Optional.of(new Receipt(new Session(r.getObject("player",UUID.class),r.getString("node"),r.getLong("epoch"),java.time.Instant.EPOCH),new Request(id,reservation),r.getBoolean("claimed")));
        }
    }
    private static byte[] encode(Reservation r)throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();try(var out=new java.io.DataOutputStream(bytes)){
            out.writeUTF(r.id().toString());out.writeUTF(r.player().toString());out.writeUTF(r.channel());var d=r.destination();
            out.writeUTF(d.node());out.writeUTF(d.world());out.writeDouble(d.x());out.writeDouble(d.y());out.writeDouble(d.z());out.writeFloat(d.yaw());out.writeFloat(d.pitch());
            out.writeLong(r.expiresAt().getEpochSecond());out.writeInt(r.expiresAt().getNano());
        }return bytes.toByteArray();
    }
    private static Reservation decode(byte[] bytes)throws Exception{
        try(var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))){
            UUID id=UUID.fromString(in.readUTF()),player=UUID.fromString(in.readUTF());String channel=in.readUTF();
            var d=new Destination(in.readUTF(),in.readUTF(),in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat(),in.readFloat());
            return new Reservation(id,player,channel,d,java.time.Instant.ofEpochSecond(in.readLong(),in.readInt()));
        }
    }
    public List<Request> pending(String source)throws Exception{
        return db.transaction(c->{
            List<Request> requests=new ArrayList<>();
            try(var s=sql(c,"SELECT q.id,r.id AS reservation,r.player,r.channel,r.expires_at,c.node,c.world,r.x,r.y,r.z,r.yaw,r.pitch FROM link_routes q JOIN link_sessions s ON s.player=q.player JOIN link_reservations r ON r.id=q.reservation JOIN link_channels c ON c.id=r.channel WHERE q.state='REQUESTED' AND s.node=? AND s.state='ACTIVE' AND r.expires_at>clock_timestamp() ORDER BY q.created_at LIMIT 64",source);var r=s.executeQuery()){
                while(r.next())requests.add(new Request(r.getObject("id",UUID.class),new Reservation(r.getObject("reservation",UUID.class),r.getObject("player",UUID.class),r.getString("channel"),new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch")),instant(r,"expires_at"))));
            }for(int i=0;i<requests.size();i++){var saved=receipt(c,requests.get(i).id());if(saved.isPresent())requests.set(i,saved.get().request());}return List.copyOf(requests);
        });
    }
    public void ready(UUID id)throws Exception{db.transaction(c->{
        var receipt=receipt(c,id);if(receipt.isPresent())lock(c,"session:"+receipt.get().owner().player());lock(c,"allocation");
        if(update(c,"UPDATE link_routes SET state='READY' WHERE id=? AND state='REQUESTED'",id)==1){
            update(c,"UPDATE link_reservations SET expires_at=clock_timestamp()+interval '30 seconds' WHERE id=(SELECT reservation FROM link_routes WHERE id=?) AND state='RESERVED'",id);
            update(c,"UPDATE link_arrivals SET expires_at=clock_timestamp()+interval '30 seconds' WHERE id=? AND state='ISSUED'",id);
        }return null;
    });}
    public List<Request> readyFor(Collection<UUID> players)throws Exception{
        if(players.isEmpty())return List.of();
        return db.transaction(c->{
            List<Request> result=new ArrayList<>();
            String placeholders=String.join(",",Collections.nCopies(players.size(),"?"));
            try(var s=sql(c,"SELECT q.id,r.id AS reservation,r.player,r.channel,r.expires_at,c.node,c.world,r.x,r.y,r.z,r.yaw,r.pitch FROM link_routes q JOIN link_reservations r ON r.id=q.reservation JOIN link_channels c ON c.id=r.channel WHERE q.state='READY' AND q.player IN ("+placeholders+") ORDER BY q.created_at LIMIT 64",players.toArray());var r=s.executeQuery()){
                while(r.next())result.add(new Request(r.getObject("id",UUID.class),new Reservation(r.getObject("reservation",UUID.class),r.getObject("player",UUID.class),r.getString("channel"),new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch")),instant(r,"expires_at"))));
            }return List.copyOf(result);
        });
    }
    public boolean dispatch(UUID id)throws Exception{return db.transaction(c->update(c,"UPDATE link_routes SET state='CONNECTING' WHERE id=? AND state='READY'",id)==1);}
    public void finish(UUID id,boolean success)throws Exception{db.transaction(c->{update(c,"UPDATE link_routes SET state=? WHERE id=? AND state IN ('REQUESTED','READY','CONNECTING')",success?"COMPLETE":"FAILED",id);return null;});}
    public String status(UUID id)throws Exception{return db.transaction(c->{try(var s=sql(c,"SELECT state FROM link_routes WHERE id=?",id);var r=s.executeQuery()){return r.next()?r.getString(1):"MISSING";}});}
}
