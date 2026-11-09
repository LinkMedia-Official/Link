package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Latest-state storage with atomic multi-aggregate commits. Name retained for binary compatibility. */
public final class EventStore {
    private final Database db;
    public EventStore(Database db) { this.db = db; }

    public Optional<State> read(String id) throws Exception {
        return db.transaction(c -> read(c, id));
    }
    public List<State> prefix(String prefix) throws Exception {
        return db.transaction(c -> {
            List<State> result=new ArrayList<>();
            try(var s=sql(c,"SELECT id,version,schema_version,payload FROM link_state WHERE starts_with(id,?) ORDER BY id",prefix);var r=s.executeQuery()){
                while(r.next())result.add(new State(r.getString(1),r.getLong(2),r.getInt(3),r.getBytes(4)));
            }return List.copyOf(result);
        });
    }
    static Optional<State> read(Connection c, String id) throws SQLException {
        try (var s = sql(c, "SELECT version,schema_version,payload FROM link_state WHERE id=?", id);
             var r = s.executeQuery()) {
            return r.next() ? Optional.of(new State(id, r.getLong(1), r.getInt(2), r.getBytes(3))) : Optional.empty();
        }
    }
    public Commit commit(Transaction tx) throws Exception {
        return db.transaction(c -> commit(c,tx));
    }
    public Commit commit(Transaction tx,Runnable progress)throws Exception{return db.transaction(c->commit(c,tx,progress));}
    Commit commit(Connection c,Transaction tx)throws Exception{return commit(c,tx,()->{});}
    private Commit commit(Connection c,Transaction tx,Runnable progress) throws Exception {
        byte[] digest = digest(tx);
            lock(c, "operation:" + tx.operation());
            try (var s = sql(c, "SELECT digest FROM link_operations WHERE id=?", tx.operation()); var r = s.executeQuery()) {
                if (r.next()) {
                    if (!MessageDigest.isEqual(digest, r.getBytes(1))) throw new ConflictException("Operation ID reused with different content");
                    return new Commit(tx.operation(), true);
                }
            }
            for (Session guard : tx.guards().stream().sorted(Comparator.comparing(s -> s.player().toString())).toList()) {
                SessionStore.requireActive(c, guard);
            }
            var changes = tx.changes().stream().sorted(Comparator.comparing(Change::id)).toList();
            for (Change change : changes) {
                // All writers (including NMS adapters) must present the current owner.
                if (change.id().startsWith("player:")) {
                    UUID player = UUID.fromString(change.id().substring(7));
                    if (tx.guards().stream().noneMatch(s -> s.player().equals(player)))
                        throw new ConflictException("Player change requires a session guard");
                }
                if (change.expectedVersion() == Long.MAX_VALUE) throw new ConflictException("Version exhausted");
            }
            update(c, "INSERT INTO link_operations(id,digest,events_pruned) VALUES (?,?,true)", tx.operation(), digest);
            // Conditional row writes protect versions without an advisory lock per key.
            // Stable ordering prevents opposite-order multi-row writers from deadlocking.
            // Every write and the receipt/outbox remain in this single SQL transaction.
            try(var insert=c.prepareStatement("INSERT INTO link_state(id,version,schema_version,payload) VALUES (?,1,?,?)");
                var replace=c.prepareStatement("UPDATE link_state SET version=?,schema_version=?,payload=? WHERE id=? AND version=?")){
                for(Change change:changes){
                    if(change.expectedVersion()==0){
                        insert.setString(1,change.id());insert.setInt(2,change.schema());insert.setBytes(3,change.after());
                        try{insert.executeUpdate();}catch(SQLException error){
                            if("23505".equals(error.getSQLState())||error.getErrorCode()==1062)
                                throw new ConflictException("Version conflict: "+change.id());
                            throw error;
                        }
                    }else{
                        replace.setLong(1,change.expectedVersion()+1);replace.setInt(2,change.schema());replace.setBytes(3,change.after());
                        replace.setString(4,change.id());replace.setLong(5,change.expectedVersion());
                        if(replace.executeUpdate()!=1)throw new ConflictException("Version conflict: "+change.id());
                    }
                    progress.run();
                }
            }
            update(c, "INSERT INTO link_outbox(id) VALUES (?)", tx.operation());
            return new Commit(tx.operation(), false);
    }
    /** Legacy, read-only history. New commits do not create events. */
    @Deprecated
    public List<Event> history(String id, long after, int limit) throws Exception {
        if (after < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid event page");
        return db.transaction(c -> {
            List<Event> events = new ArrayList<>();
            try (var s = sql(c, "SELECT operation,version,schema_version,payload,type,created_at FROM link_events WHERE aggregate_id=? AND version>? ORDER BY version LIMIT ?", id, after, limit);
                 var r = s.executeQuery()) {
                while (r.next()) events.add(new Event(r.getObject(1, UUID.class), new State(id,r.getLong(2),r.getInt(3),r.getBytes(4)),r.getString(5),instant(r,6)));
            }
            return List.copyOf(events);
        });
    }
    /** Reads legacy history only; never used for recovery. New revisions have no historical replay. */
    @Deprecated
    public Optional<State> replay(String id, long version) throws Exception {
        if (version < 0) throw new IllegalArgumentException("Invalid revision");
        return db.transaction(c -> {
            try (var s = sql(c, "SELECT version,schema_version,payload FROM link_events WHERE aggregate_id=? AND version<=? ORDER BY version DESC LIMIT 1", id,version); var r = s.executeQuery()) {
                return r.next() ? Optional.of(new State(id,r.getLong(1),r.getInt(2),r.getBytes(3))) : Optional.empty();
            }
        });
    }
    static byte[] digest(Transaction tx) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(tx.guards().size());
            for (Session s : tx.guards().stream().sorted(Comparator.comparing(g -> g.player().toString())).toList()) {
                out.writeUTF(s.player().toString()); out.writeUTF(s.node()); out.writeLong(s.epoch());
            }
            out.writeInt(tx.changes().size());
            for (Change c : tx.changes().stream().sorted(Comparator.comparing(Change::id)).toList()) {
                out.writeUTF(c.id()); out.writeLong(c.expectedVersion()); out.writeInt(c.schema()); out.writeUTF(c.type());
                byte[] payload = c.after(); out.writeInt(payload.length); out.write(payload);
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    }
}
