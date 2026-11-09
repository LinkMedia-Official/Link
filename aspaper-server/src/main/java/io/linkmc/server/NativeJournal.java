package io.linkmc.server;

import io.linkmc.api.LinkApi.Destination;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

/**
 * Java entry point called by the Link Minecraft patches. The implementation is Scala ({@code NativeJournalImpl});
 * these methods only forward to it. Keep the signatures stable: the patches compile against them.
 */
public final class NativeJournal {
    private NativeJournal() {}
    private static final LinkServerBridge BRIDGE = LinkServerBridge.load();

    public static boolean enabled() { return BRIDGE.journalEnabled(); }
    public static void run(Runnable action) { BRIDGE.run(action); }
    public static boolean runTask(java.util.function.BooleanSupplier task) { return BRIDGE.runTask(task); }
    public static boolean moving(UUID player) { return BRIDGE.moving(player); }
    public static void initializeEarly() { BRIDGE.initializeEarly(); }
    public static void prefetchArrival(UUID player, Connection connection) { BRIDGE.prefetchArrival(player, connection); }
    public static boolean arrivalReady(UUID player, Connection connection) { return BRIDGE.arrivalReady(player, connection); }
    public static void prepare(UUID player, Connection connection) { BRIDGE.prepare(player, connection); }
    public static Destination destination(UUID id) { return BRIDGE.destination(id); }
    public static Optional<CompoundTag> playerData(UUID id) { return BRIDGE.playerData(id); }
    public static Optional<CompoundTag> readPlayerData(UUID id) { return BRIDGE.readPlayerData(id); }
    public static void joined(ServerPlayer player) { BRIDGE.joined(player); }
    public static void left(ServerPlayer player) { BRIDGE.left(player); }
    public static com.google.gson.JsonElement stats(UUID id) { return BRIDGE.stats(id); }
    public static com.google.gson.JsonElement advancements(UUID id) { return BRIDGE.advancements(id); }
    public static Optional<CompoundTag> savedData(net.minecraft.world.level.storage.SavedDataStorage storage, java.nio.file.Path path, String id) { return BRIDGE.savedData(storage, path, id); }
    public static void seedStorage(String world, CompoundTag seed) { BRIDGE.seedStorage(world, seed); }
    public static void registerStorage(net.minecraft.world.level.storage.SavedDataStorage storage, java.nio.file.Path path) { BRIDGE.registerStorage(storage, path); }
    public static CompletableFuture<Void> saveAsync() { return BRIDGE.saveAsync(); }
}
