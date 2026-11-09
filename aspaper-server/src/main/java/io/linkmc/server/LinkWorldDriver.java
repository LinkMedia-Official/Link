package io.linkmc.server;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.SlimeNMSBridge;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import io.linkmc.api.LinkApi.*;
import io.linkmc.core.*;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.*;
import java.util.concurrent.*;

/** Native server adapter. Snapshot operations always capture on the server thread. */
public final class LinkWorldDriver implements WorldService.Driver {
    private final AdvancedSlimePaperAPI asp=AdvancedSlimePaperAPI.instance();
    private final EventStore store;
    private final Session guard;
    private final String node;
    private final WorldBlobs blobs;
    private final NetworkStore network;
    private final Map<String,Boolean> managed=new ConcurrentHashMap<>();
    private final Map<String,Long> versions=new ConcurrentHashMap<>();
    List<String> editableNames(){return managed.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();}
    List<String> loadedNames(){return List.copyOf(managed.keySet());}
    public LinkWorldDriver(EventStore store,Session guard,String node,WorldBlobs blobs,NetworkStore network){this.store=store;this.guard=guard;this.node=node;this.blobs=blobs;this.network=network;}
    private String key(String name){io.linkmc.api.LinkApi.requireName(name);return "world:"+node+"/"+name;}
    static <T> T main(Callable<T> task) throws Exception {
        return main(task,true);
    }
    private static <T> T readMain(Callable<T> task)throws Exception{return main(task,false);}
    private static <T> T main(Callable<T> task,boolean durable) throws Exception {
        if(Bukkit.isPrimaryThread())return task.call();
        if(MinecraftServer.getServer().isStopped())throw new IllegalStateException("Server stopped before world operation could run");
        var future=new CompletableFuture<T>();
        if(!NativeJournal.watch(future))return future.get();
        MinecraftServer.getServer().execute(() -> {if(future.isCancelled())return;try{T value=task.call();if(durable)NativeJournal.complete(future,value);else future.complete(value);}catch(Throwable e){future.completeExceptionally(e);if(NativeJournal.enabled()&&!(e instanceof ConflictException)&&!(e instanceof IllegalArgumentException))throw new IllegalStateException("World operation failed",e);}});
        // No timeout: otherwise a timed-out operation might still create a world later.
        try{return future.get();}catch(InterruptedException e){future.cancel(false);Thread.currentThread().interrupt();throw e;}
    }
    private SlimeWorldInstance live(String name){
        if(!managed.containsKey(name))throw new ConflictException("Not a Link-managed world: "+name);
        World world=Bukkit.getWorld(name);
        if(world==null)throw new ConflictException("World is not loaded");
        return Objects.requireNonNull(SlimeNMSBridge.instance().getInstance(world));
    }
    private static SlimePropertyMap properties(){
        SlimePropertyMap props=new SlimePropertyMap();
        props.setValue(SlimeProperties.SAVE_BLOCK_TICKS,true);
        props.setValue(SlimeProperties.SAVE_FLUID_TICKS,true);
        props.setValue(SlimeProperties.SAVE_POI,true);
        props.setValue(SlimeProperties.SPAWN_Y,65);
        return props;
    }
    public SlimeWorld bootstrap(String name,byte[] template)throws Exception {
        SlimeWorld world=asp.getSerializer().deserializeWorld(name,template,null,properties(),true);
        managed.put(name,false);return world;
    }
    public void restoreNative(String name,byte[] bytes)throws Exception{
        var world=asp.getSerializer().deserializeWorld(name,bytes,null,properties(),true);
        asp.loadWorld(world,true);managed.put(name,NativeJournal.persistentWorld(name));
    }
    private boolean newBootstrapEditor;
    public SlimeWorld bootstrapEditor(String name)throws Exception {
        byte[] saved=null;
        if(NativeJournal.enabled()){
            if(NativeJournal.knownWorld(name))saved=NativeJournal.storedWorld(name);
            else NativeJournal.createdWorld(name,true);
        }else{
            var state=store.read(key(name));
            if(state.isPresent()){
                if(state.get().payload().length==0)throw new ConflictException("Bootstrap editor was deleted");
                saved=blobs.decode(state.get().payload());versions.put(name,state.get().version());
            }else versions.put(name,0L);
        }
        newBootstrapEditor=saved==null;
        managed.put(name,true);
        return saved==null?asp.createEmptyWorld(name,true,properties(),null)
            :asp.getSerializer().deserializeWorld(name,saved,null,properties(),true);
    }
    public void prepareBootstrapEditor(String name){
        var world=Bukkit.getWorld(name);
        world.setAutoSave(false);
        if(newBootstrapEditor){
            world.getWorldBorder().setSize(256);
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)world.getBlockAt(x,64,z).setType(org.bukkit.Material.BEDROCK);
            world.setSpawnLocation(0,65,0);
        }
    }
    public WorldInfo createEditor(String name)throws Exception {
        key(name);
        var stored=store.read(key(name));
        if(stored.isPresent())throw ConflictException.worldExists(name,stored.get().payload().length==0);
        return main(() -> {
            NativeJournal.requireNewWorld(name);
            SlimeWorld world=asp.createEmptyWorld(name,true,properties(),null);
            SlimeWorldInstance loaded=asp.loadWorld(world,true);
            managed.put(name,true);versions.put(name,0L);
            NativeJournal.createdWorld(name,true);
            loaded.getBukkitWorld().setAutoSave(false);
            loaded.getBukkitWorld().getWorldBorder().setSize(256);
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)loaded.getBukkitWorld().getBlockAt(x,64,z).setType(org.bukkit.Material.BEDROCK);
            loaded.getBukkitWorld().setSpawnLocation(0,65,0);
            return new WorldInfo(name,true,0);
        });
    }
    private record Captured(SlimeWorld world,ChannelPolicy policy){}
    private Captured capture(String name)throws Exception {
        key(name);
        return readMain(() -> {
            var result=LinkChunkSnapshots.freeze((SlimeWorld)live(name));
            if(NativeJournal.enabled()){
                var saved=new net.minecraft.nbt.CompoundTag();
                ((org.bukkit.craftbukkit.CraftWorld)Bukkit.getWorld(name)).getHandle().getDataStorage().linkSnapshot().forEach((id,tag)->{
                    if(!id.equals("paper:metadata"))saved.put(id,tag);
                });
                result.getExtraData().put("LinkSavedData",com.infernalsuite.asp.Converter.convertTag(saved));
            }
            return new Captured(result,LinkTemplateGui.policy(Bukkit.getWorld(name)));
        });
    }
    public byte[] snapshot(String name)throws Exception {return asp.getSerializer().serializeWorld(capture(name).world());}
    public WorldService.Publication publication(String name)throws Exception {var copy=capture(name);return new WorldService.Publication(asp.getSerializer().serializeWorld(copy.world()),copy.policy());}
    public WorldInfo instantiate(String name,byte[] srf,boolean persistent)throws Exception {
        key(name);
        var stored=store.read(key(name));
        if(stored.isPresent())throw ConflictException.worldExists(name,stored.get().payload().length==0);
        SlimeWorld world=asp.getSerializer().deserializeWorld(name,srf,null,properties(),true);
        WorldInfo result=main(() -> {
            NativeJournal.requireNewWorld(name);
            var instance=asp.loadWorld(world,true);managed.put(name,persistent);versions.put(name,0L);
            NativeJournal.createdWorld(name,persistent);
            instance.getBukkitWorld().setAutoSave(false);
            return new WorldInfo(name,persistent,instance.getBukkitWorld().getPlayerCount());
        });
        if(persistent)save(name);
        return result;
    }
    public WorldInfo load(String name)throws Exception {
        key(name);
        if(NativeJournal.enabled()){
            WorldInfo info=main(()->{
            if(managed.containsKey(name))throw new ConflictException("World already loaded");
            var world=asp.getSerializer().deserializeWorld(name,NativeJournal.storedWorld(name),null,properties(),true);
            asp.loadWorld(world,true);managed.put(name,NativeJournal.persistentWorld(name));
            Bukkit.getWorld(name).setAutoSave(false);return new WorldInfo(name,NativeJournal.persistentWorld(name),0);
            });
            network.openWarpWorld(node,name);return info;
        }
        State state=store.read(key(name)).orElseThrow(() -> new ConflictException("Unknown stored world"));
        if(state.payload().length==0)throw new ConflictException("World was deleted");
        SlimeWorld world=asp.getSerializer().deserializeWorld(name,blobs.decode(state.payload()),null,properties(),true);
        return main(() -> {
            asp.loadWorld(world,true);managed.put(name,true);versions.put(name,state.version());
            Bukkit.getWorld(name).setAutoSave(false);return new WorldInfo(name,true,0);
        });
    }
    public void save(String name)throws Exception {
        key(name);
        if(NativeJournal.enabled()){main(()->{
            live(name);
            if(!Boolean.TRUE.equals(managed.get(name)))throw new ConflictException("Temporary worlds are never saved");
            NativeJournal.run(()->{});return null;
        });return;}
        if(!Boolean.TRUE.equals(managed.get(name)))throw new ConflictException("Temporary worlds are never saved");
        byte[] bytes=snapshot(name);
        long version=versions.get(name);
        store.commit(new Transaction(UUID.randomUUID(),List.of(guard),List.of(new Change(key(name),version,1,"world:checkpoint",blobs.encode(key(name),bytes)))));
        versions.put(name,version+1);
    }
    public void unload(String name)throws Exception {
        key(name);
        main(() -> {
            World world=live(name).getBukkitWorld();
            if(world.equals(Bukkit.getWorlds().getFirst()))throw new ConflictException("Cannot unload the bootstrap world");
            if(!world.getPlayers().isEmpty())throw new ConflictException("World still has players");
            return null;
        });
        if(Boolean.TRUE.equals(managed.get(name)))save(name);
        if(NativeJournal.enabled())network.closeWorld(node,name);
        main(() -> {
            if(!Bukkit.unloadWorld(name,false))throw new ConflictException("World unload rejected");
            managed.remove(name);versions.remove(name);return null;
        });
    }
    public void delete(String name)throws Exception {
        key(name);
        if(managed.containsKey(name))throw new ConflictException("Unload the world before deleting it");
        if(NativeJournal.enabled()){main(()->{NativeJournal.deleteWorld(name);return null;});return;}
        State state=store.read(key(name)).orElseThrow(() -> new ConflictException("Unknown world"));
        store.commit(new Transaction(UUID.randomUUID(),List.of(guard),List.of(new Change(key(name),state.version(),1,"world:deleted",new byte[0]))));
    }
    public List<WorldInfo> loaded()throws Exception {
        return readMain(() -> managed.entrySet().stream().map(e -> new WorldInfo(e.getKey(),e.getValue(),live(e.getKey()).getBukkitWorld().getPlayerCount())).toList());
    }
    public boolean exists(String name)throws Exception {return NativeJournal.enabled()?readMain(()->NativeJournal.knownWorld(name)):store.read(key(name)).isPresent();}
    public ChannelPolicy channelPolicy(String name)throws Exception {return readMain(()->LinkTemplateGui.policy(live(name).getBukkitWorld()));}
    public Destination spawn(String name)throws Exception {
        return readMain(()->{var pos=LinkTemplateGui.spawn(live(name).getBukkitWorld());return new Destination(node,name,pos.getX(),pos.getY(),pos.getZ(),pos.getYaw(),pos.getPitch());});
    }
    public void saveOnShutdown() throws Exception {
        for(String name:List.copyOf(managed.keySet()))if(Boolean.TRUE.equals(managed.get(name)))save(name);
    }
}
