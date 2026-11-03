package io.linkmc.server;

import com.infernalsuite.asp.api.SlimeNMSBridge;
import io.linkmc.api.LinkApi;
import io.linkmc.core.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;

/** Native bootstrap; tooling mode is explicitly NOT a durable gameplay implementation. */
public final class LinkRuntime implements LinkApi {
    private static volatile LinkRuntime instance;
    private final LinkService service;
    private final WorldService worlds;
    private final LinkEditors editors;
    private final LinkWorldDriver driver;
    private final SessionStore leases;
    private final Database database;
    private final String node;
    private final NetworkStore networkStore;
    private RedisOutbox redis;
    private LinkTeleportRuntime teleportRuntime;
    private volatile boolean closing;
    private final ScheduledExecutorService notifications=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Notifications").factory());
    private final ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Retention").factory());
    private SpawnProvisioner provisioner;
    private boolean templateEditor;
    private String bootstrapWorld="link-spawn";
    private volatile Session nodeLease;
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Heartbeat").factory());
    private LinkRuntime(Database db,String node)throws Exception {
        this.database=db;this.node=node;networkStore=new NetworkStore(db);
        leases=new SessionStore(db,30);
        nodeLease=NativeJournal.enabled()?NativeJournal.startupOwner():leases.acquire(UUID.nameUUIDFromBytes(("link-node:"+node).getBytes(StandardCharsets.UTF_8)),node);
        service=new LinkService(db,NativeJournal::submitRoute);
        driver=new LinkWorldDriver(new EventStore(db),nodeLease,node,db.worldBlobs(),networkStore);
        worlds=new WorldService(driver,new TemplateStore(db));
        editors=new LinkEditors(worlds);
    }
    /** Called from PaperWorldLoader before any default dimension is constructed. */
    public static void beforeWorlds(){
        var config=LinkConfiguration.get();
        String mode=config.mode();
        if(mode.equals("disabled"))return;
        if(!mode.equals("tools")&&!mode.equals("journal"))throw new IllegalStateException("Link strict gameplay mode is not yet validated. Use journal only in an isolated integration environment.");
        if(instance!=null)throw new IllegalStateException("Link initialized twice");
        if(mode.equals("journal")){
            var forwarding=io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity;
            if(!forwarding.enabled||!forwarding.onlineMode||forwarding.secret.isBlank())throw new IllegalStateException("Link journal requires authenticated Velocity modern forwarding");
        }
        try {
            String url=config.jdbcUrl(),user=config.username(),password=config.password();
            String node=config.node();
            Database db=NativeJournal.enabled()?NativeJournal.database():Database.pooled(url,user,password);if(!NativeJournal.enabled())db.worldStorage(config.worldStorage());db.migrate();
            LinkRuntime runtime=new LinkRuntime(db,node);
            try {
                String template=config.template();
                int version=config.templateVersion();
                var published=new TemplateStore(db).find(template,version);
                runtime.templateEditor=published.isEmpty();
                if(runtime.templateEditor){
                    runtime.bootstrapWorld="link-template-editor";
                    SlimeNMSBridge.instance().setDefaultWorlds(runtime.driver.bootstrapEditor(runtime.bootstrapWorld),null,null);
                    net.minecraft.server.MinecraftServer.LOGGER.warn("Spawn template {}:{} is missing. Opening link-template-editor. Build here, run /link publish link-template-editor {} {}, then restart.",template,version,template,version);
                }else{
                    SlimeNMSBridge.instance().setDefaultWorlds(runtime.driver.bootstrap("link-spawn",NativeJournal.worldOrTemplate("link-spawn",published.get())),null,null);
                }
                instance=runtime;RuntimeProvider.install(runtime);
                runtime.heartbeat.scheduleWithFixedDelay(() -> {
                    try {
                        runtime.nodeLease=runtime.leases.renew(runtime.nodeLease);NativeJournal.renew();
                        if(NativeJournal.enabled()){
                            runtime.networkStore.heartbeat(node,config.nodeCapacity());
                            if(runtime.provisioner!=null)runtime.provisioner.ensureSpare(0,65,0,0,0).whenComplete((created,error)->{if(error!=null)net.minecraft.server.MinecraftServer.LOGGER.error("Link spawn provisioning failed",error);});
                        }
                    }
                    catch(Exception e){
                        net.minecraft.server.MinecraftServer.LOGGER.error("Link lost its DB lease; stopping to fence this backend",e);
                        org.bukkit.Bukkit.shutdown();
                    }
                },5,5,TimeUnit.SECONDS);
                NativeJournal.finishStartup();
            } catch(Exception e){runtime.close();throw e;}
        }catch(Exception e){throw new IllegalStateException("Link bootstrap failed",e);}
    }
    public static boolean managesWorlds(){return instance!=null;}
    public static void afterWorlds(){
        if(instance==null)return;
        LinkRuntime runtime=instance;
        var config=LinkConfiguration.get();
        try{
            if(runtime.templateEditor)runtime.driver.prepareBootstrapEditor(runtime.bootstrapWorld);
            if(NativeJournal.enabled()){
                for(String name:NativeJournal.recoveredWorlds())if(!name.equals(runtime.bootstrapWorld))runtime.driver.restoreNative(name,NativeJournal.recoveredWorld(name));
                net.minecraft.server.MinecraftServer.getServer().linkRestoreSavedData();
                NativeJournal.activate();
                runtime.networkStore.heartbeat(runtime.node,config.nodeCapacity());
                runtime.networkStore.reconcileNode(runtime.nodeLease);
                runtime.networkStore.syncWarpWorlds(runtime.node,runtime.driver.loadedNames());
                for(var world:org.bukkit.Bukkit.getWorlds())if(runtime.templateEditor?world.getName().equals(runtime.bootstrapWorld):(world.getName().equals("link-spawn")||world.getName().startsWith("spawn-"))){
                    var pos=world.getSpawnLocation();
                    runtime.networkStore.register(runtime.node+":"+world.getName(),new Destination(runtime.node,world.getName(),pos.getX(),pos.getY(),pos.getZ(),pos.getYaw(),pos.getPitch()),config.worldCapacity());
                }
                if(!runtime.templateEditor)runtime.provisioner=new SpawnProvisioner(runtime.worlds,runtime.networkStore,runtime.node,config.template(),config.templateVersion(),config.worldCapacity(),config.maxWorlds());
            }
            String redisUri=config.redisUri();
            if(redisUri!=null&&!redisUri.isBlank())runtime.notifications.scheduleWithFixedDelay(()->runtime.deliverNotifications(redisUri),0,5,TimeUnit.SECONDS);
            var completions=new LinkCompletions(runtime.driver::loadedNames,config.template());
            runtime.notifications.scheduleWithFixedDelay(()->{
                try{completions.refresh(runtime.database,runtime.node);}
                catch(Exception error){net.minecraft.server.MinecraftServer.LOGGER.debug("Link completion catalog refresh failed",error);}
            },0,2,TimeUnit.SECONDS);
            LinkCommand.register(runtime,completions);
            LinkTravelCommand.register(runtime,completions);
            LinkWarpCommand.register(runtime.network(),runtime.node,config.worldCapacity(),completions);
            if(NativeJournal.enabled()){
                runtime.teleportRuntime=new LinkTeleportRuntime(runtime.database,runtime.service,runtime.node,config.worldCapacity());
                LinkTeleportCommand.register(runtime.teleports(),runtime.teleportRuntime);runtime.teleportRuntime.start();
            }
            if(config.retentionEnabled()){
                net.minecraft.server.MinecraftServer.LOGGER.info("Link event retention enabled: {} hours; latest state/transaction anchors retained",config.retentionHours());
                runtime.maintenance.scheduleWithFixedDelay(()->runtime.pruneEvents(config),config.retentionInterval(),config.retentionInterval(),TimeUnit.SECONDS);
            }
        }catch(Exception e){throw new IllegalStateException("Link world activation failed",e);}
    }
    public static void stop(){LinkRuntime runtime=instance;if(runtime!=null){instance=null;RuntimeProvider.uninstall(runtime);runtime.close();}}
    static void fence(){LinkRuntime runtime=instance;if(runtime!=null)runtime.heartbeat.shutdownNow();}
    private synchronized void deliverNotifications(String uri){
        if(closing)return;
        try{
            if(redis==null)redis=new RedisOutbox(database,uri);
            redis.flush(64);
        }catch(Exception e){net.minecraft.server.MinecraftServer.LOGGER.warn("Redis delivery deferred; SQL outbox retained",e);}
    }
    private synchronized void closeNotifications(){if(redis!=null){redis.close();redis=null;}}
    private void pruneEvents(LinkConfiguration config){
        int events=0,operations=0,notifications=0,requests=0;
        try{
            var retention=new EventRetention(database);
            for(int i=0;i<config.retentionBatches()&&!closing&&!Thread.currentThread().isInterrupted();i++){
                var result=retention.prune(config.retentionHours(),config.retentionBatch());
                events+=result.events();operations+=result.operations();notifications+=result.notifications();requests+=result.requests();
                if(result.operations()<config.retentionBatch()&&result.notifications()<config.retentionBatch()&&result.requests()<config.retentionBatch())break;
            }
            if(events>0||notifications>0||requests>0)net.minecraft.server.MinecraftServer.LOGGER.info("Link retention: pruned {} events from {} historical operations, {} delivered notifications and {} expired requests",events,operations,notifications,requests);
        }catch(Exception error){net.minecraft.server.MinecraftServer.LOGGER.warn("Link retention deferred; current state preserved",error);}
    }
    private void close(){
        closing=true;notifications.shutdownNow();maintenance.shutdownNow();if(teleportRuntime!=null)teleportRuntime.close();
        worlds.close();
        try {if(NativeJournal.enabled())NativeJournal.shutdown();else driver.saveOnShutdown();}
        catch(Exception e){net.minecraft.server.MinecraftServer.LOGGER.error("Link final checkpoint failed; ownership will not be explicitly released",e);}
        finally {heartbeat.shutdownNow();closeNotifications();service.close();database.close();}
    }
    public Data data(){return service.data();}
    public Sessions sessions(){return service.sessions();}
    public Network network(){return service.network();}
    public Teleports teleports(){return service.teleports();}
    public Worlds worlds(){return worlds;}
    public Editors editors(){return editors;}
    public Engine engine(){return new Engine(){
        public boolean journalEnabled(){return NativeJournal.enabled();}
        public CompletionStage<Void> transact(Runnable mutation){
            java.util.Objects.requireNonNull(mutation);
            CompletableFuture<Void> result=new CompletableFuture<>();
            if(!NativeJournal.enabled())return CompletableFuture.failedFuture(new IllegalStateException("Native journal disabled"));
            if(!NativeJournal.watch(result))return result;
            net.minecraft.server.MinecraftServer.getServer().execute(()->{
                try{NativeJournal.run(()->{mutation.run();NativeJournal.complete(result,null);});}
                catch(Throwable error){result.completeExceptionally(error);throw error;}
            });
            return result;
        }
    };}
}
