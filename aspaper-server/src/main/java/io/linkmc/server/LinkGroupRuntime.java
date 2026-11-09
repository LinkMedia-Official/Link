package io.linkmc.server;

import io.linkmc.api.LinkApi;
import io.linkmc.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

final class LinkGroupRuntime implements AutoCloseable {
    private final WarpGroupStore store;private final LinkApi.Worlds worlds;private final String node;private final Supplier<LinkApi.Session> owner;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Warp-Groups").factory());
    private final Set<String> busy=ConcurrentHashMap.newKeySet();private volatile boolean closed;
    LinkGroupRuntime(Database db,LinkApi.Worlds worlds,String node,Supplier<LinkApi.Session> owner){store=new WarpGroupStore(db);this.worlds=worlds;this.node=node;this.owner=owner;}
    void start(){worker.scheduleWithFixedDelay(this::poll,0,1,TimeUnit.SECONDS);}
    private void poll(){
        if(closed||LinkRuntime.isClosing())return;
        try{
            for(var pending:store.pending(node)){
                var route=store.dispatch(pending);route.ifPresent(NativeJournal::submitRoute);
                if(route.isEmpty()&&store.status(pending.id()).equals("FAILED"))net.minecraft.server.MinecraftServer.getServer().execute(()->{var player=org.bukkit.Bukkit.getPlayer(pending.player());if(player!=null)player.sendMessage("워프 채널 배정이 만료되었거나 접속 상태가 바뀌었습니다. 정원·서버 상태를 확인하고 다시 시도하세요.");});
            }
            store.provisionDemand();
            for(var job:store.jobs(node))if(busy.add(job.id()))worlds.ensurePersistent(job.world(),job.template(),job.version())
                .thenCompose(w->worlds.spawn(w.name())).whenComplete((spawn,error)->{
                    try{if(!closed){if(error!=null)net.minecraft.server.MinecraftServer.LOGGER.warn("Warp channel creation deferred: {}",job.id(),error);else store.ready(job,spawn,owner.get());}}
                    catch(Exception e){net.minecraft.server.MinecraftServer.LOGGER.warn("Warp channel activation deferred",e);}
                    finally{busy.remove(job.id());}
                });
        }catch(Exception e){net.minecraft.server.MinecraftServer.LOGGER.warn("Warp group polling deferred",e);}
    }
    @Override public void close(){
        closed=true;worker.shutdownNow();
        try{if(!worker.awaitTermination(15,TimeUnit.SECONDS))throw new IllegalStateException("Group scheduler did not stop");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while stopping group scheduler",e);}
    }
}
