package io.linkmc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Link's asynchronous, server-independent plugin contract. No method may be
 * joined on a Minecraft tick thread. Payload codecs belong to their namespace;
 * callers must retain readers for previously published schema versions.
 * Native Minecraft state is only writable through the server's NMS adapter.
 */
public interface LinkApi {
    Data data();
    Sessions sessions();
    Network network();
    default Teleports teleports(){throw new IllegalStateException("No teleport request service");}
    interface Teleports {
        CompletionStage<TeleportRequest> request(UUID requester,String recipient,boolean here);
        /** Null requester name selects the only pending request; multiple requests require a name. */
        CompletionStage<TeleportRequest> respond(UUID recipient,String requester,boolean accept);
        CompletionStage<Boolean> cancel(UUID requester);
        CompletionStage<List<TeleportRequest>> incoming(UUID recipient);
        CompletionStage<List<String>> onlinePlayers();
    }
    record TeleportRequest(UUID id,UUID requester,String requesterName,UUID recipient,String recipientName,boolean here,String state,Instant expiresAt){}
    /** Available on a Link backend; proxies only expose data/session/network services. */
    default Worlds worlds() { throw new IllegalStateException("No world engine on this node"); }
    default Engine engine() { throw new IllegalStateException("No native engine on this node"); }
    default Editors editors() { throw new IllegalStateException("No template editor on this node"); }

    /** Backend-only editing excursions. Implementations retain the original return position. */
    interface Editors {
        CompletionStage<WorldInfo> create(UUID player, String workspace);
        CompletionStage<WorldInfo> edit(UUID player, String template, int version);
        CompletionStage<Void> enter(UUID player, String workspace);
        CompletionStage<Void> save(UUID player, String template, int version, boolean exit);
        /** Saves the working world and returns; does not publish a template version. */
        CompletionStage<Void> exit(UUID player);
    }

    interface Engine {
        boolean journalEnabled();
        /**
         * Executes on the server thread, completing only after the containing native
         * frame commits. Do not join on the tick thread. Mutation failure fences and
         * stops the backend; it does not roll Java objects back in place. External
         * side effects (HTTP, files, another plugin's database) are not transactional.
         */
        CompletionStage<Void> transact(Runnable mutation);
    }

    interface Worlds {
        CompletionStage<List<String>> templates();
        CompletionStage<WorldInfo> createEditor(String name);
        CompletionStage<Void> publish(String editor, String template, int version);
        CompletionStage<WorldInfo> create(String name, String template, int version, boolean persistent);
        CompletionStage<WorldInfo> load(String name);
        CompletionStage<Void> save(String name);
        CompletionStage<Void> unload(String name);
        CompletionStage<Void> delete(String name);
        CompletionStage<List<WorldInfo>> loaded();
    }
    record WorldInfo(String name, boolean persistent, int players) {}

    interface Data {
        CompletionStage<Optional<State>> read(String id);
        CompletionStage<Commit> commit(Transaction transaction);
        /** Retention may prune historical revisions. Results remain ordered but may contain gaps; read() returns current state. */
        CompletionStage<List<Event>> history(String id, long afterVersion, int limit);
    }

    interface Sessions {
        CompletionStage<Session> acquire(UUID player, String node);
        CompletionStage<Session> renew(Session session);
        CompletionStage<Void> release(Session session);
        /** Freezes writes by the source, persisting the destination and final version. */
        CompletionStage<Transfer> prepare(Session source, UUID operation, Destination destination);
        /** Atomically claims a prepared transfer and increments the fencing epoch. */
        CompletionStage<Session> accept(UUID player, UUID transfer, String destinationNode);
    }

    interface Network {
        CompletionStage<List<Channel>> channels();
        CompletionStage<Void> registerChannel(String id, Destination spawn, int capacity);
        CompletionStage<Void> setChannelReady(String id, boolean ready);
        CompletionStage<Reservation> reserve(UUID player, UUID request);
        CompletionStage<Reservation> reserve(UUID player, UUID request, Destination destination);
        CompletionStage<Void> cancel(UUID reservation, UUID player);
        CompletionStage<Optional<Destination>> warp(String name);
        CompletionStage<Void> setWarp(String name, Destination destination);
        /** Registers a loaded destination world for warp travel without making it a public spawn. */
        CompletionStage<Void> setWarp(String name, Destination destination, int capacity);
        CompletionStage<Boolean> deleteWarp(String name);
        CompletionStage<List<String>> warps();
        /** Enqueues native source checkpoint and proxy travel; null destination allocates a spawn. */
        CompletionStage<Reservation> requestTravel(UUID player, UUID operation, Destination destination);
        /** REQUESTED, READY, CONNECTING, COMPLETE, FAILED or MISSING. */
        CompletionStage<String> travelStatus(UUID operation);
    }

    record Destination(String node, String world, double x, double y, double z, float yaw, float pitch) {
        public Destination {
            requireName(node); requireName(world);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Non-finite position");
        }
    }
    record Session(UUID player, String node, long epoch, Instant expiresAt) {
        public Session {
            java.util.Objects.requireNonNull(player); requireName(node);
            java.util.Objects.requireNonNull(expiresAt);
            if (epoch < 1) throw new IllegalArgumentException("Invalid epoch");
        }
    }
    /** Immutable binary state, including the encoding schema version. */
    record State(String id, long version, int schema, byte[] payload) {
        public State { requireName(id); payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    /** Expected version 0 creates a new aggregate. */
    record Change(String id, long expectedVersion, int schema, String type, byte[] after) {
        public Change {
            requireName(id); requireName(type);
            if (expectedVersion < 0 || schema < 1) throw new IllegalArgumentException("Invalid version");
            after = after.clone();
            if (after.length > 16 * 1024 * 1024) throw new IllegalArgumentException("State exceeds 16 MiB");
        }
        @Override public byte[] after() { return after.clone(); }
    }
    /**
     * All changes and the durable outbox entry commit atomically. A retry MUST
     * reuse both operation ID and exact payload. Guards fence all participant
     * player sessions; only trusted engine code may write unguarded world data.
     */
    record Transaction(UUID operation, List<Session> guards, List<Change> changes) {
        public Transaction {
            java.util.Objects.requireNonNull(operation);
            guards = List.copyOf(guards); changes = List.copyOf(changes);
            if (changes.isEmpty() || changes.size() > 1024) throw new IllegalArgumentException("Invalid change count");
            if (changes.stream().map(Change::id).distinct().count() != changes.size())
                throw new IllegalArgumentException("Duplicate aggregate");
            if (guards.stream().map(Session::player).distinct().count() != guards.size())
                throw new IllegalArgumentException("Duplicate session");
        }
    }
    record Commit(UUID operation, boolean duplicate) {}
    /** After-state events are replayed without invoking gameplay or plugin callbacks. */
    record Event(UUID operation, State state, String type, Instant createdAt) {}
    record Transfer(UUID id, UUID player, long finalVersion, Destination destination) {}
    record Channel(String id, String node, String world, int capacity, int occupied, int reserved) {}
    record Reservation(UUID id, UUID player, String channel, Destination destination, Instant expiresAt) {}

    /** Stable bounded event aggregate identifier, including for 160-character warp names. */
    static String warpStateId(String name){
        requireName(name);
        try{return "warp:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new AssertionError(error);}
    }
    static void requireName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_./:-]{1,160}") || value.contains(".."))
            throw new IllegalArgumentException("Invalid Link identifier");
    }
}
