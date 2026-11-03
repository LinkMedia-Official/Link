package io.linkmc.server;

import io.linkmc.api.LinkApi;
import io.linkmc.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.persistence.PersistentDataType;

/** Resolves consented destinations on the destination player's own server thread. */
final class LinkTeleportRuntime implements AutoCloseable {
    private final TeleportStore store;
    private final LinkService service;
    private final String node;
    private final int capacity;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Teleport-Requests").factory());
    private final Set<UUID> busy=ConcurrentHashMap.newKeySet();
    private final Map<UUID,String> seen=new HashMap<>();
    private volatile List<String> online=List.of();
    private volatile List<LinkApi.TeleportRequest> notices=List.of();
    private volatile boolean closed;
    LinkTeleportRuntime(Database db,LinkService service,String node,int capacity){this.store=new TeleportStore(db);this.service=service;this.node=node;this.capacity=capacity;}
    void start(){worker.scheduleWithFixedDelay(this::poll,0,1,TimeUnit.SECONDS);}
    List<String> suggest(UUID player,String action,String prefix){
        var candidates=action.equals("tpaccept")||action.equals("tpdeny")
            ?notices.stream().filter(r->r.recipient().equals(player)&&r.state().equals("PENDING")).map(LinkApi.TeleportRequest::requesterName).toList():online;
        return candidates.stream().filter(n->n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).distinct().sorted().toList();
    }
    private void poll(){
        if(closed)return;
        try{
            online=store.onlinePlayers();notices=store.notices(node);var snapshot=notices;
            net.minecraft.server.MinecraftServer.getServer().execute(()->notifyPlayers(snapshot));
            for(var task:store.work(node))if(busy.add(task.request().id()))net.minecraft.server.MinecraftServer.getServer().execute(()->resolve(task));
        }catch(Exception error){net.minecraft.server.MinecraftServer.LOGGER.warn("Teleport request polling deferred",error);}
    }
    private void resolve(TeleportStore.Work task){
        UUID id=task.request().id();
        if(closed){busy.remove(id);return;}
        if(task.destination()!=null){submit(task,task.destination());return;}
        UUID host=task.request().here()?task.request().requester():task.request().recipient();
        var player=Bukkit.getPlayer(host);
        if(player==null||!player.isOnline()||NativeJournal.moving(host)||player.getPersistentDataContainer().has(LinkEditors.KEY,PersistentDataType.STRING)){
            service.rejectTeleport(id).whenComplete((v,e)->busy.remove(id));return;
        }
        var location=player.getLocation();
        var destination=task.destination()!=null?task.destination():new LinkApi.Destination(node,location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch());
        submit(task,destination);
    }
    private void submit(TeleportStore.Work task,LinkApi.Destination destination){
        service.submitTeleport(task,destination,capacity).whenComplete((v,error)->{
            busy.remove(task.request().id());
            if(error!=null)net.minecraft.server.MinecraftServer.LOGGER.debug("Teleport request was deferred or rejected",error);
        });
    }
    private void notifyPlayers(List<LinkApi.TeleportRequest> snapshot){
        if(closed)return;
        Set<UUID> current=new HashSet<>();
        for(var request:snapshot){
            current.add(request.id());
            var from=Bukkit.getPlayer(request.requester());var to=Bukkit.getPlayer(request.recipient());
            if(request.state().equals("PENDING")?to==null:from==null)continue;
            if(request.state().equals(seen.put(request.id(),request.state())))continue;
            if(request.state().equals("PENDING")&&to!=null){
                to.sendMessage(request.requesterName()+(request.here()?"님이 자신의 위치로 와 달라는 티피 요청을 보냈습니다.":"님이 당신에게 이동하는 티피 요청을 보냈습니다."));
                to.sendMessage("/티피수락 "+request.requesterName()+" 또는 /티피거절 "+request.requesterName()+" (요청은 60초 후 만료)");
            }else if(from!=null&&Set.of("ACCEPTED","DISPATCHED","DECLINED","CANCELLED","EXPIRED","FAILED").contains(request.state())){
                from.sendMessage("티피 요청: "+request.recipientName()+" — "+switch(request.state()){case "ACCEPTED","DISPATCHED"->"상대가 수락했습니다. 이동 요청을 처리합니다.";case "DECLINED"->"상대가 거절했습니다.";case "CANCELLED"->"취소되었습니다.";case "EXPIRED"->"만료되었습니다.";default->"이동 준비에 실패했습니다. 접속 상태와 정원을 확인하세요.";});
            }
        }
        seen.keySet().retainAll(current);
    }
    @Override public void close(){closed=true;worker.shutdownNow();}
}
