package io.linkmc.server;

import io.linkmc.api.LinkApi.Destination;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * The only way Java (patched Minecraft/Paper code) reaches the Scala implementation of Link's server runtime.
 * Java is compiled before Scala and cannot reference Scala classes, so the implementation
 * ({@code io.linkmc.server.LinkServerImpl}) is located once through {@link ServiceLoader}.
 */
public interface LinkServerBridge {
    // NativeJournal
    boolean journalEnabled();
    void run(Runnable action);
    boolean runTask(BooleanSupplier task);
    boolean moving(UUID player);
    void initializeEarly();
    void prefetchArrival(UUID player, Connection connection);
    boolean arrivalReady(UUID player, Connection connection);
    void prepare(UUID player, Connection connection);
    Destination destination(UUID id);
    Optional<CompoundTag> playerData(UUID id);
    Optional<CompoundTag> readPlayerData(UUID id);
    void joined(ServerPlayer player);
    void left(ServerPlayer player);
    com.google.gson.JsonElement stats(UUID id);
    com.google.gson.JsonElement advancements(UUID id);
    Optional<CompoundTag> savedData(SavedDataStorage storage, Path path, String id);
    void seedStorage(String world, CompoundTag seed);
    void registerStorage(SavedDataStorage storage, Path path);
    CompletableFuture<Void> saveAsync();
    // LinkRuntime
    void beforeWorlds();
    void afterWorlds();
    void beginShutdown();
    void stop();
    boolean managesWorlds();

    static LinkServerBridge load() {
        return ServiceLoader.load(LinkServerBridge.class, LinkServerBridge.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Link server implementation missing from the server jar"));
    }
}
