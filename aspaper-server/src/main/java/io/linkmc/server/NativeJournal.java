package io.linkmc.server;

import com.infernalsuite.asp.api.*;
import io.linkmc.api.LinkApi.*;
import io.linkmc.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.Connection;
import org.bukkit.Bukkit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** In-memory gameplay with bounded asynchronous autosaves and explicit ownership-transfer barriers. */
public final class NativeJournal {
    private static volatile NativeJournal instance;
    private static volatile Session startupLease;
    private static ScheduledExecutorService startupRenewal;
    /** Runs before Minecraft constructs global clocks, maps and other SavedData. */
    public static synchronized void initializeEarly(){
        var config=LinkConfiguration.get();
        if(!config.mode().equals("journal")||enabled())return;
        Database db=null;
        startupLease=null;
        try{
            String node=config.node();
            db=Database.pooled(config.jdbcUrl(),config.username(),config.password());db.worldStorage(config.worldStorage());db.migrate();
            SessionStore leases=new SessionStore(db,30);
            startupLease=leases.acquireNode(node);
            db.nodeOwner(()->startupLease);
            initialize(db,node,()->startupLease);
            startupRenewal=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("Link-Startup-Lease").factory());
            startupRenewal.scheduleWithFixedDelay(()->{try{startupLease=leases.renew(startupLease);}catch(Exception e){fenceOwnership();MinecraftServer.LOGGER.error("Link startup ownership lost",e);}},5,5,TimeUnit.SECONDS);
        }catch(Exception e){
            finishStartup();
            var journal=instance;instance=null;
            if(journal!=null)try{journal.savePipeline.close();}catch(Exception cleanup){e.addSuppressed(cleanup);}
            if(db!=null){
                try{if(startupLease!=null)new SessionStore(db,30).releaseNode(startupLease);}catch(Exception cleanup){e.addSuppressed(cleanup);}
                db.close();
            }
            throw new IllegalStateException("Cannot initialize durable native storage",e);
        }
    }
    static Session startupOwner(){return startupLease;}
    static Database database(){return instance.db;}
    static void finishStartup(){if(startupRenewal!=null){startupRenewal.shutdownNow();startupRenewal=null;}}
    private final Database db;
    private final EventStore store;
    private final FrameCommitter frames;
    private final LinkChunkSnapshots chunkSnapshots=new LinkChunkSnapshots();
    private final AsyncSavePipeline savePipeline=new AsyncSavePipeline(LinkConfiguration.get().saveWorkers());
    private final long saveIntervalNanos=TimeUnit.SECONDS.toNanos(LinkConfiguration.get().saveIntervalSeconds());
    private long nextSaveNanos;
    private final SaveProgress saveProgress=new SaveProgress();
    private CompletableFuture<Map<String,byte[]>> saving;
    private List<Runnable> savedCompletions=List.of();
    private List<java.util.function.Consumer<Throwable>> savedFailures=List.of();
    private Set<UUID> savedDepartures=Set.of();
    private record CapturedWorld(String key,com.infernalsuite.asp.api.world.SlimeWorld world) {}
    private final SessionStore sessions;
    private final ArrivalStore arrivals;
    private final String node;
    private final java.util.function.Supplier<Session> nodeLease;
    private final Map<String,byte[]> restored=new HashMap<>();
    private final Map<String,Byte> catalog=new HashMap<>();
    private final Map<UUID,ArrivalStore.Arrival> admitted=new ConcurrentHashMap<>();
    private final Map<UUID,Connection> connections=new ConcurrentHashMap<>();
    private final Map<UUID,CompoundTag> playerData=new ConcurrentHashMap<>();
    private final Map<UUID,ServerPlayer> players=new ConcurrentHashMap<>();
    private final Set<UUID> departing=new HashSet<>();
    private final Set<UUID> localTravel=new HashSet<>();
    private final Set<UUID> moving=ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedRoutes=ConcurrentHashMap.newKeySet();
    private final Map<String,net.minecraft.world.level.storage.SavedDataStorage> storages=new ConcurrentHashMap<>();
    private final Map<String,CompoundTag> storageSeeds=new HashMap<>();
    private final Map<String,byte[]> storageMigrations=new HashMap<>();
    private java.nio.file.Path globalStoragePath;
    private final List<Runnable> completions=new ArrayList<>();
    private final List<java.util.function.Consumer<Throwable>> failures=new ArrayList<>();
    private final Set<CompletableFuture<?>> requests=ConcurrentHashMap.newKeySet();
    private int depth;
    private volatile boolean poisoned;
    private volatile boolean active;
    private NativeJournal(Database db,String node,java.util.function.Supplier<Session> lease)throws Exception{
        this.db=db;this.node=node;nodeLease=lease;store=new EventStore(db);frames=new FrameCommitter(tx->{saveProgress.phase("sql",tx.changes().size());return store.commit(tx,saveProgress::advance);});
        sessions=new SessionStore(db,30);arrivals=new ArrivalStore(db);
        for(State state:store.prefix("native:"+node+"/")){frames.restore(state);restored.put(state.id(),state.payload());}
        String prefix="native:"+node+"/catalog/";
        restored.forEach((key,value)->{if(key.startsWith(prefix)&&value.length==1)catalog.put(key.substring(prefix.length()),value[0]);});
        MinecraftServer.LOGGER.info("Link asynchronous storage: interval={}s, workers={}, maximum in-flight checkpoints=1",LinkConfiguration.get().saveIntervalSeconds(),LinkConfiguration.get().saveWorkers());
    }
    static void initialize(Database db,String node,java.util.function.Supplier<Session> lease)throws Exception{instance=new NativeJournal(db,node,lease);}
    public static boolean enabled(){return instance!=null;}
    static void fenceOwnership(){var j=instance;if(j!=null)j.poisoned=true;}
    public static boolean moving(UUID player){return enabled()&&instance.moving.contains(player);}
    public static boolean runTask(java.util.function.BooleanSupplier task){
        boolean[] result={false};run(()->result[0]=task.getAsBoolean());return result[0];
    }
    public static void run(Runnable action){
        NativeJournal journal=instance;if(journal==null||!journal.active){action.run();return;}
        if(!Bukkit.isPrimaryThread()){
            if(MinecraftServer.getServer().hasStopped())return;
            throw new IllegalStateException("Native frame must run on the server thread");
        }
        // Shutdown drains scheduled tasks. Once fenced, discard their actions rather
        // than throwing from the drain and preventing the server's cleanup from running.
        if(journal.poisoned)return;
        boolean outer=journal.depth++==0;
        try{
            if(outer)journal.finishSave(false);
            action.run();
            if(outer)journal.checkpoint();
        }catch(Throwable error){
            if(!journal.poisoned){
                journal.poisoned=true;LinkPacketGate.abort(error);LinkRuntime.fence();finishStartup();
                journal.requests.forEach(f->f.completeExceptionally(error));journal.requests.clear();
                journal.failures.forEach(f->f.accept(error));journal.savedFailures.forEach(f->f.accept(error));journal.failures.clear();journal.completions.clear();
                MinecraftServer.LOGGER.error("Link asynchronous save failed; stopping to preserve the last committed checkpoint",error);
                MinecraftServer.getServer().halt(false);
            }
            if(error instanceof Error e)throw e;
            throw new IllegalStateException("Link native transaction failed",error);
        }finally{journal.depth--;}
    }
    private void checkpoint()throws Exception{
        finishSave(false);
        if(saving!=null)return;
        if(completions.isEmpty()&&departing.isEmpty()&&System.nanoTime()<nextSaveNanos)return;
        List<CapturedWorld> capturedWorlds=new ArrayList<>();
        Map<String,CompoundTag> tags=new TreeMap<>();
        var server=MinecraftServer.getServer();
        server.getScoreboard().storeToSaveDataIfDirty(server.getDataStorage().computeIfAbsent(net.minecraft.world.scores.ScoreboardSaveData.TYPE));
        List<Session> owners=new ArrayList<>();owners.add(nodeLease.get());
        Map<String,byte[]> snapshots=new TreeMap<>();
        snapshots.putAll(storageMigrations);
        storages.put("native:"+node+"/saved/global",server.getDataStorage());
        List<String> worldNames=new ArrayList<>();
        for(var world:Bukkit.getWorlds()){
            storages.put("native:"+node+"/saved/world/"+world.getName(),((org.bukkit.craftbukkit.CraftWorld)world).getHandle().getDataStorage());
            var slime=SlimeNMSBridge.instance().getInstance(world);
            if(slime==null)throw new IllegalStateException("Native journal only accepts slime worlds: "+world.getName());
            worldNames.add(world.getName());
            capturedWorlds.add(new CapturedWorld(worldKey(world.getName()),LinkChunkSnapshots.freeze((com.infernalsuite.asp.api.world.SlimeWorld)slime)));
        }
        Collections.sort(worldNames);snapshots.put("native:"+node+"/worlds",String.join("\n",worldNames).getBytes(StandardCharsets.UTF_8));
        catalog.forEach((name,state)->snapshots.put("native:"+node+"/catalog/"+name,new byte[]{state}));
        for(var storage:storages.entrySet())for(var entry:storage.getValue().linkSnapshot().entrySet()){
            tags.put(storage.getKey()+"/"+entry.getKey(),entry.getValue().copy());
        }
        for(var entry:players.entrySet()){
            if(moving.contains(entry.getKey()))continue;
            var admission=admitted.get(entry.getKey());if(admission==null)throw new IllegalStateException("Player without DB owner");
            owners.add(admission.session());
            tags.put("player:"+entry.getKey(),NativePlayerCodec.capture(entry.getValue()));
        }
        Map<String,byte[]> previous=Map.copyOf(restored);
        storageMigrations.clear();
        savedCompletions=List.copyOf(completions);completions.clear();
        savedFailures=List.copyOf(failures);failures.clear();
        savedDepartures=Set.copyOf(departing);
        // Keep departing players registered until commit so heartbeat renewal cannot release ownership early.
        departing.removeAll(savedDepartures);
        nextSaveNanos=System.nanoTime()+saveIntervalNanos;
        saveProgress.begin();
        saving=savePipeline.submit(()->{
            for(CapturedWorld world:capturedWorlds)chunkSnapshots.capture(world.world(),world.key(),db.worldBlobs(),previous,snapshots,savePipeline,saveProgress);
            List<Callable<Map.Entry<String,byte[]>>> compression=new ArrayList<>();
            tags.forEach((key,tag)->compression.add(()->Map.entry(key,NativePlayerCodec.compress(tag))));
            saveProgress.phase("player-and-world-data",compression.size());
            for(var value:savePipeline.parallel(compression,saveProgress::advance))snapshots.put(value.getKey(),value.getValue());
            frames.commit(owners,snapshots,()->{});
            return snapshots;
        });
    }
    /** Completion runs on the server thread; workers never invoke Bukkit/plugin callbacks. */
    private void finishSave(boolean wait)throws Exception{
        if(saving==null)return;
        if(!wait&&!saving.isDone()){
            saveProgress.check(LinkConfiguration.get().saveStallSeconds(),LinkConfiguration.get().saveTimeoutSeconds());
            if(saveProgress.reportDue())MinecraftServer.LOGGER.info("Link save in progress: {}",saveProgress.status());
            return;
        }
        Map<String,byte[]> result=saveProgress.await(saving,LinkConfiguration.get().saveStallSeconds(),LinkConfiguration.get().saveTimeoutSeconds(),status->MinecraftServer.LOGGER.info("Link save in progress: {}",status));
        if(wait)MinecraftServer.LOGGER.info("Link save completed: {}",saveProgress.status());
        restored.putAll(result);saving=null;
        List<Runnable> completed=savedCompletions;savedCompletions=List.of();savedFailures=List.of();
        completed.forEach(Runnable::run);
        for(UUID id:savedDepartures){
            var admission=admitted.remove(id);
            players.remove(id);connections.remove(id);playerData.remove(id);moving.remove(id);departing.remove(id);
            // Publish RELEASED only after this backend has forgotten the old connection.
            if(admission!=null)sessions.disconnect(admission.session());
        }
        savedDepartures=Set.of();

    }
    /** Explicit save / ownership hand-off barrier. Ordinary ticks never wait for storage. */
    static void flush()throws Exception{
        var j=instance;if(j==null)return;
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Capture must run on the server thread");
        j.finishSave(true);j.nextSaveNanos=0;j.checkpoint();j.finishSave(true);
    }
    public static CompletableFuture<Void> saveAsync(){
        var future=new CompletableFuture<Void>();
        if(!watch(future))return future;
        run(()->complete(future,null));
        return future;
    }
    static <T> void complete(CompletableFuture<T> future,T value){
        NativeJournal j=instance;
        if(j!=null&&j.active&&j.depth>0){j.completions.add(()->future.complete(value));j.failures.add(future::completeExceptionally);}
        else future.complete(value);
    }
    static boolean watch(CompletableFuture<?> future){
        NativeJournal j=instance;if(j==null)return true;
        j.requests.add(future);future.whenComplete((v,e)->j.requests.remove(future));
        if(j.poisoned){future.completeExceptionally(new IllegalStateException("Link journal fenced"));return false;}
        return true;
    }
    static void activate(){if(instance.poisoned)throw new IllegalStateException("Startup lease was lost");instance.active=true;run(()->{try{flush();}catch(Exception e){throw new IllegalStateException(e);}});}
    private synchronized String storageKey(java.nio.file.Path path){
        var normalized=path.toAbsolutePath().normalize();
        var worlds=java.nio.file.Path.of("link-saved-worlds").toAbsolutePath().normalize();
        String legacy="native:"+node+"/saved/"+UUID.nameUUIDFromBytes(normalized.toString().getBytes(StandardCharsets.UTF_8));
        String key;
        if(normalized.startsWith(worlds))key="native:"+node+"/saved/world/"+worlds.relativize(normalized).toString().replace('\\','/');
        else{
            if(globalStoragePath==null)globalStoragePath=normalized;
            key=normalized.equals(globalStoragePath)?"native:"+node+"/saved/global":legacy;
        }
        // Preserve early development snapshots while moving to location-independent
        // global/world keys. Copies are journaled atomically in the next frame.
        if(!key.equals(legacy)){
            Map<String,byte[]> copies=new HashMap<>();
            for(var entry:restored.entrySet())if(entry.getKey().startsWith(legacy+"/")){
                String target=key+entry.getKey().substring(legacy.length());
                if(!restored.containsKey(target))copies.put(target,entry.getValue());
            }
            restored.putAll(copies);storageMigrations.putAll(copies);
        }
        return key;
    }
    public static Optional<CompoundTag> savedData(net.minecraft.world.level.storage.SavedDataStorage storage,java.nio.file.Path path,String id){
        NativeJournal j=instance;String key=j.storageKey(path);j.storages.put(key,storage);
        var saved=j.frames.state(key+"/"+id).map(state->{try{return NativePlayerCodec.decode(state.payload());}catch(Exception e){throw new IllegalStateException("Invalid durable saved data",e);}});
        if(saved.isPresent())return saved;
        byte[] migrated=j.restored.get(key+"/"+id);
        if(migrated!=null)try{return Optional.of(NativePlayerCodec.decode(migrated));}catch(Exception e){throw new IllegalStateException("Invalid migrated saved data",e);}
        return Optional.ofNullable(j.storageSeeds.get(key)).flatMap(seed->seed.getCompound(id)).map(CompoundTag::copy);
    }
    public static void seedStorage(String world,CompoundTag seed){if(enabled())instance.storageSeeds.put(instance.storageKey(java.nio.file.Path.of("link-saved-worlds",world)),seed.copy());}
    public static void registerStorage(net.minecraft.world.level.storage.SavedDataStorage storage,java.nio.file.Path path){if(enabled())instance.storages.put(instance.storageKey(path),storage);}
    private String worldKey(String name){return "native:"+node+"/world/"+name;}
    private static Optional<byte[]> snapshot(String name)throws Exception{
        instance.finishSave(true);
        return LinkChunkSnapshots.restore(name,instance.worldKey(name),instance.db.worldBlobs(),instance.restored);
    }
    static byte[] worldOrTemplate(String name,byte[] template)throws Exception{
        if(!enabled())return template;
        instance.catalog.putIfAbsent(name,(byte)0);
        return snapshot(name).orElse(template);
    }
    static void createdWorld(String name,boolean persistent){
        if(enabled()){
            Byte previous=instance.catalog.putIfAbsent(name,(byte)(persistent?1:0));
            if(previous!=null)throw ConflictException.worldExists(name,previous==2);
        }
    }
    static void requireNewWorld(String name){
        if(knownWorld(name))throw ConflictException.worldExists(name,Objects.equals(instance.catalog.get(name),(byte)2));
    }
    static boolean knownWorld(String name){return enabled()&&instance.catalog.containsKey(name);}
    static boolean persistentWorld(String name){return enabled()&&Objects.equals(instance.catalog.get(name),(byte)1);}
    static byte[] storedWorld(String name)throws Exception{
        if(!instance.catalog.containsKey(name)||Objects.equals(instance.catalog.get(name),(byte)2))throw new ConflictException("Unknown or deleted world");
        return snapshot(name).orElseThrow(()->new ConflictException("World has no committed snapshot"));
    }
    static void deleteWorld(String name){
        if(!knownWorld(name))throw new ConflictException("Unknown world");instance.catalog.put(name,(byte)2);
    }
    static List<String> recoveredWorlds(){
        if(!enabled())return List.of();
        byte[] data=instance.restored.get("native:"+instance.node+"/worlds");
        return data==null?List.of():Arrays.asList(new String(data,StandardCharsets.UTF_8).split("\n"));
    }
    static byte[] recoveredWorld(String name)throws Exception{return snapshot(name).orElseThrow();}
    public static void prepare(UUID player,Connection connection){
        if(!enabled())return;
        NativeJournal j=instance;
        Connection previous=j.connections.putIfAbsent(player,connection);
        if(previous==connection)return;
        if(previous!=null)throw new IllegalStateException("Duplicate player connection");
        try{
            var arrival=j.arrivals.claim(player,j.node);j.admitted.put(player,arrival);
            Optional<State> saved=j.store.read("player:"+player);
            if(saved.isPresent()){j.frames.restore(saved.get());j.playerData.put(player,NativePlayerCodec.decode(saved.get().payload()));}
        }catch(Exception e){
            var arrival=j.admitted.remove(player);j.connections.remove(player,connection);j.playerData.remove(player);
            if(arrival!=null)try{j.sessions.disconnect(arrival.session());}catch(Exception cleanup){e.addSuppressed(cleanup);}
            throw new IllegalStateException("Link arrival rejected",e);
        }
    }
    /** Bukkit OfflinePlayer/pre-login reads see committed data without claiming a live session. */
    public static Optional<CompoundTag> readPlayerData(UUID id){
        var journal=instance;if(journal==null)return Optional.empty();
        try{var state=journal.store.read("player:"+id);return state.isEmpty()?Optional.empty():Optional.of(NativePlayerCodec.decode(state.get().payload()));}
        catch(Exception error){throw new IllegalStateException("Cannot read committed player data",error);}
    }
    public static Optional<CompoundTag> playerData(UUID id){
        if(!instance.admitted.containsKey(id))throw new IllegalStateException("Player data requested before arrival claim");
        return Optional.ofNullable(instance.playerData.get(id)).map(CompoundTag::copy);
    }
    public static Destination destination(UUID id){return Objects.requireNonNull(instance.admitted.get(id),"Missing arrival").destination();}
    public static void joined(ServerPlayer player){
        if(!enabled())return;
        var j=instance;j.players.put(player.getUUID(),player);
        try{new TeleportStore(j.db).registerPlayer(player.getUUID(),player.getBukkitEntity().getName());}
        catch(Exception error){throw new IllegalStateException("Player directory registration failed",error);}
        CompoundTag tag=j.playerData.get(player.getUUID());if(tag!=null)NativePlayerCodec.recover(player,tag);
        LinkUtilities.restoreFlight(player.getBukkitEntity());
    }
    public static void left(ServerPlayer player){if(enabled())instance.departing.add(player.getUUID());}
    public static com.google.gson.JsonElement stats(UUID id){
        if(!enabled())return null;
        CompoundTag tag=instance.playerData.get(id);
        return tag==null?null:tag.getString("LinkStats").map(com.google.gson.JsonParser::parseString).orElse(null);
    }
    public static com.google.gson.JsonElement advancements(UUID id){
        if(!enabled())return null;
        CompoundTag tag=instance.playerData.get(id);
        return tag==null?null:tag.getString("LinkAdvancements").map(com.google.gson.JsonParser::parseString).orElse(null);
    }
    static void renew()throws Exception{
        if(!enabled()||instance.poisoned)return;
        NativeJournal j=instance;
        for(var entry:List.copyOf(j.admitted.entrySet())){
            if(j.moving.contains(entry.getKey())){
                Connection source=j.connections.get(entry.getKey());
                if(source!=null&&source.isConnected())j.sessions.renewMoving(entry.getValue().session());
                continue;
            }
            var old=entry.getValue();
            try{
                Connection connection=j.connections.get(entry.getKey());
                if(connection!=null&&!connection.isConnected()&&!j.players.containsKey(entry.getKey())){
                    if(j.admitted.remove(entry.getKey(),old)){j.connections.remove(entry.getKey(),connection);j.playerData.remove(entry.getKey());j.sessions.disconnect(old.session());}
                    continue;
                }
                Session renewed=j.sessions.renew(old.session());
                j.admitted.replace(entry.getKey(),old,new ArrivalStore.Arrival(old.ticket(),renewed,old.destination()));
            }catch(ConflictException stale){if(j.admitted.get(entry.getKey())==old&&!j.moving.contains(entry.getKey()))throw stale;}
        }
        for(var route:new RouteStore(j.db).pending(j.node))submitRoute(route);
    }
    static void submitRoute(RouteStore.Request route){
        NativeJournal j=instance;
        if(j==null||j.poisoned||LinkRuntime.isClosing()||!j.queuedRoutes.add(route.id()))return;
        try{
            MinecraftServer.getServer().execute(()->{
                try{if(!j.poisoned)run(()->j.transfer(route));}
                finally{j.queuedRoutes.remove(route.id());}
            });
        }catch(RuntimeException error){j.queuedRoutes.remove(route.id());throw error;}
    }
    private void transfer(RouteStore.Request route){
        UUID id=route.reservation().player();
        if(moving.contains(id)||localTravel.contains(id))return;
        ServerPlayer player=players.get(id);if(player==null)return;
        var routes=new RouteStore(db);var admission=admitted.get(id);if(admission==null)return;
        Session owner=admission.session();boolean local=route.reservation().destination().node().equals(node);
        try{
            if(!routes.status(route.id()).equals("REQUESTED"))return;
            if(player.getBukkitEntity().getPersistentDataContainer().has(LinkEditors.KEY,org.bukkit.persistence.PersistentDataType.STRING)){
                routes.reject(route);player.getBukkitEntity().sendMessage("템플릿 편집을 종료한 뒤 이동하세요.");return;
            }
            var d=route.reservation().destination();var world=local?Bukkit.getWorld(d.world()):null;
            if(local&&world==null){routes.reject(route);player.getBukkitEntity().sendMessage("이동할 월드가 로드되지 않았습니다.");return;}
            // Validate and claim before any gameplay mutation. Expected request conflicts are scoped to this route.
            try{if(!routes.claim(route,owner))return;}
            catch(ConflictException rejected){routes.reject(route);player.getBukkitEntity().sendMessage("이동 요청이 만료되었거나 이전 접속의 요청입니다. 다시 시도하세요.");return;}
            player.closeContainer();player.stopRiding();
            if(local){
                localTravel.add(id);
                if(!player.getBukkitEntity().teleport(new org.bukkit.Location(world,d.x(),d.y(),d.z(),d.yaw(),d.pitch()))){
                    localTravel.remove(id);routes.reject(route);player.getBukkitEntity().sendMessage("플러그인이 이동을 취소했습니다.");return;
                }
                // Keep ACTIVE ownership and include the player in the post-teleport checkpoint.
                completions.add(()->{try{
                    Destination arrived=routes.completeLocal(route,owner);
                    admitted.computeIfPresent(id,(key,current)->new ArrivalStore.Arrival(current.ticket(),current.session(),arrived));
                }catch(Exception e){throw new IllegalStateException("Local route acknowledgement failed",e);}
                finally{localTravel.remove(id);}});
                return;
            }
            flush();moving.add(id);
            try{routes.prepareRemote(route,owner);}
            catch(ConflictException rejected){moving.remove(id);routes.reject(route);player.getBukkitEntity().sendMessage("서버 이동 요청이 만료되었습니다. 다시 시도하세요.");return;}
            player.level().removePlayerImmediately(player,net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER);
            // READY follows the durable source removal checkpoint. Only Velocity opens the new connection.
            completions.add(()->{try{routes.ready(route.id());}catch(Exception e){throw new IllegalStateException("Remote route acknowledgement failed",e);}});
        }catch(Exception e){throw new IllegalStateException("Transfer preparation failed",e);}
    }
    static void shutdown(){
        finishStartup();
        NativeJournal j=instance;if(j==null)return;
        try{
            if(j.poisoned)throw new IllegalStateException("Backend ownership or checkpoint failed");
            if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Final checkpoint requires the server thread");
            run(()->{try{flush();}catch(Exception e){throw new IllegalStateException(e);}});
        }finally{
            j.active=false;LinkRuntime.fence();
            j.requests.forEach(f->f.completeExceptionally(new IllegalStateException("Backend stopped")));j.requests.clear();
            try{j.savePipeline.close();}finally{instance=null;}
        }
    }
}
