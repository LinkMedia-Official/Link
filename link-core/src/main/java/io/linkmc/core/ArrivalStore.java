package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Authenticated proxy -> backend handoff via SQL, never client plugin messages. */
public final class ArrivalStore {
    public record Arrival(UUID ticket, Session session, Destination destination) {}
    private final Database db;
    private final SessionStore sessions;
    private final NetworkStore network;
    public ArrivalStore(Database db) { this.db=db;sessions=new SessionStore(db,30);network=new NetworkStore(db); }
    public void issue(UUID ticket, Reservation reservation, UUID transfer) throws Exception {
        db.transaction(c -> {issue(c,ticket,reservation,transfer);return null;});
    }
    void issue(java.sql.Connection c,UUID ticket,Reservation reservation,UUID transfer)throws Exception{
            lock(c,"session:"+reservation.player());lock(c,"allocation");
            update(c,"UPDATE link_arrivals SET state='CANCELLED' WHERE player=? AND state='ISSUED' AND expires_at<=clock_timestamp()",reservation.player());
            try(var s=sql(c,"SELECT * FROM link_arrivals WHERE id=?",ticket);var r=s.executeQuery()) {
                if(r.next()) {
                    if(!r.getObject("player",UUID.class).equals(reservation.player()) || !r.getObject("reservation",UUID.class).equals(reservation.id()) || !Objects.equals(r.getObject("transfer",UUID.class),transfer))
                        throw new ConflictException("Arrival ID reused");
                    if(!r.getString("state").equals("ISSUED"))throw new ConflictException("Arrival no longer pending");
                    return;
                }
            }
            try(var s=sql(c,"SELECT c.node FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE r.id=? AND r.player=? AND r.state='RESERVED' AND r.expires_at>clock_timestamp() AND c.ready",reservation.id(),reservation.player());var r=s.executeQuery()) {
                if(!r.next() || !r.getString(1).equals(reservation.destination().node()))throw new ConflictException("Invalid arrival reservation");
            }
            update(c,"INSERT INTO link_arrivals(id,player,reservation,transfer,node,state,expires_at) SELECT ?,?,?,?,?,'ISSUED',expires_at FROM link_reservations WHERE id=?",
                    ticket,reservation.player(),reservation.id(),transfer,reservation.destination().node(),reservation.id());
    }
    /** Single-use claim. The backend caches the returned result for its one connection. */
    public Arrival claim(UUID player,String node) throws Exception {
        return db.transaction(c -> {
            sharedLock(c,"node-startup:"+node);
            lock(c,"session:"+player);
            try(var s=sql(c,"SELECT * FROM link_arrivals WHERE player=? AND state='ISSUED' AND expires_at>clock_timestamp() FOR UPDATE",player);var r=s.executeQuery()) {
                if(!r.next() || !r.getString("node").equals(node))throw new ConflictException("No valid arrival for this backend");
                UUID transfer=r.getObject("transfer",UUID.class),ticket=r.getObject("id",UUID.class);
                Session owner=transfer==null?sessions.acquire(c,player,node):sessions.accept(c,player,transfer,node);
                Destination destination=network.admit(c,r.getObject("reservation",UUID.class),owner);
                update(c,"UPDATE link_arrivals SET state='CLAIMED',epoch=? WHERE id=?",owner.epoch(),ticket);
                return new Arrival(ticket,owner,destination);
            }
        });
    }
    public void cancel(UUID ticket,UUID player) throws Exception {
        db.transaction(c -> {
            lock(c,"session:"+player);lock(c,"allocation");
            try(var s=sql(c,"SELECT reservation FROM link_arrivals WHERE id=? AND player=? AND state='ISSUED' FOR UPDATE",ticket,player);var r=s.executeQuery()) {
                if(r.next()){
                    update(c,"UPDATE link_arrivals SET state='CANCELLED' WHERE id=?",ticket);
                    update(c,"DELETE FROM link_reservations WHERE id=? AND state='RESERVED'",r.getObject(1,UUID.class));
                }
            }return null;
        });
    }
}
