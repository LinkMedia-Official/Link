package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** Private, persistent editing worlds; never registered as public spawn channels. */
final class LinkEditors implements LinkApi.Editors {
    static final class EditorError extends IllegalArgumentException { EditorError(String message){super(message);} }
    static final NamespacedKey KEY=new NamespacedKey("link","template-editor");
    record Visit(String workspace, UUID returnWorld, double x,double y,double z,float yaw,float pitch) {}
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    private final LinkApi.Worlds worlds;
    private final java.util.function.Consumer<Runnable> dispatch;
    private final Set<UUID> busy=ConcurrentHashMap.newKeySet();
    LinkEditors(LinkApi.Worlds worlds){this(worlds,task->net.minecraft.server.MinecraftServer.getServer().execute(task));}
    LinkEditors(LinkApi.Worlds worlds,java.util.function.Consumer<Runnable> dispatch){this.worlds=worlds;this.dispatch=dispatch;}

    private <T> CompletionStage<T> exclusive(UUID id,Supplier<CompletionStage<T>> operation){
        if(!busy.add(id))return CompletableFuture.failedFuture(new EditorError("이전 템플릿 작업이 진행 중입니다."));
        try{return operation.get().whenComplete((value,error)->busy.remove(id));}
        catch(Exception error){busy.remove(id);return CompletableFuture.failedFuture(error);}
    }
    private <T> CompletionStage<T> main(Supplier<T> action){
        var future=new CompletableFuture<T>();
        if(!NativeJournal.watch(future))return future;
        try{dispatch.accept(()->NativeJournal.run(()->{
            try{NativeJournal.complete(future,action.get());}
            catch(IllegalArgumentException error){future.completeExceptionally(error);}
            catch(RuntimeException error){future.completeExceptionally(error);throw error;}
        }));}catch(RuntimeException error){future.completeExceptionally(error);}
        return future;
    }
    static Player player(UUID id){
        Player player=Bukkit.getPlayer(id);
        if(player==null||!player.isOnline())throw new EditorError("플레이어가 접속 중이 아닙니다.");
        if(NativeJournal.moving(id))throw new EditorError("서버 이동 중에는 편집할 수 없습니다.");
        return player;
    }
    static Visit visit(Player player){
        String value=player.getPersistentDataContainer().get(KEY,PersistentDataType.STRING);
        if(value==null)return null;
        try{
            Visit visit=JSON.fromJson(value,Visit.class);
            if(visit==null||visit.returnWorld()==null)throw new IllegalArgumentException();
            LinkApi.requireName(visit.workspace());
            if(!Double.isFinite(visit.x())||!Double.isFinite(visit.y())||!Double.isFinite(visit.z())||!Float.isFinite(visit.yaw())||!Float.isFinite(visit.pitch()))throw new IllegalArgumentException();
            return visit;
        }catch(RuntimeException error){throw new EditorError("편집 복귀 정보가 손상되었습니다. 관리자에게 문의하세요.");}
    }
    private static void available(UUID id){
        if(visit(player(id))!=null)throw new EditorError("이미 편집 중입니다. /템플릿 나가기 후 새 작업을 시작하세요.");
    }
    static Visit required(Player player){
        Visit visit=visit(player);
        if(visit==null)throw new EditorError("편집 중인 템플릿이 없습니다. /템플릿 생성 또는 편집으로 시작하세요.");
        return visit;
    }
    private CompletionStage<Void> enterWorld(UUID id,String workspace){
        return worlds.loaded().thenCompose(loaded->{
            if(loaded.stream().noneMatch(w->w.name().equals(workspace)))
                return worlds.load(workspace).thenCompose(v->enterWorld(id,workspace));
            if(loaded.stream().noneMatch(w->w.name().equals(workspace)&&w.persistent()))
                return CompletableFuture.failedFuture(new EditorError("임시 월드는 편집 작업 월드로 사용할 수 없습니다."));
            return main(()->{
                Player player=player(id);Visit previous=visit(player);
                if(previous!=null&&!previous.workspace().equals(workspace))throw new EditorError("다른 작업을 편집 중입니다. 먼저 /템플릿 나가기를 실행하세요.");
                World world=Bukkit.getWorld(workspace);
                if(world==null)throw new EditorError("작업 월드가 언로드되었습니다.");
                Location back=player.getLocation();
                Visit next=previous!=null?previous:new Visit(workspace,back.getWorld().getUID(),back.getX(),back.getY(),back.getZ(),back.getYaw(),back.getPitch());
                if(!player.teleport(LinkTemplateGui.spawn(world)))throw new EditorError("다른 플러그인이 편집 월드 입장을 취소했습니다.");
                player.getPersistentDataContainer().set(KEY,PersistentDataType.STRING,JSON.toJson(next));
                return null;
            });
        });
    }
    public CompletionStage<LinkApi.WorldInfo> create(UUID id,String workspace){
        LinkApi.requireName(workspace);
        return exclusive(id,()->main(()->{available(id);return null;})
            .thenCompose(v->worlds.createEditor(workspace)).thenCompose(info->worlds.save(workspace)
                .thenCompose(v->enterWorld(id,workspace)).thenApply(v->info)));
    }
    public CompletionStage<LinkApi.WorldInfo> edit(UUID id,String template,int version){
        LinkApi.requireName(template);positive(version);
        String workspace="edit-"+UUID.randomUUID();
        return exclusive(id,()->main(()->{available(id);return null;})
            .thenCompose(v->worlds.create(workspace,template,version,true))
            .thenCompose(info->enterWorld(id,workspace).thenApply(v->info)));
    }
    public CompletionStage<Void> enter(UUID id,String workspace){
        LinkApi.requireName(workspace);return exclusive(id,()->enterWorld(id,workspace));
    }
    public CompletionStage<Void> openSettings(UUID id){
        return main(()->{Player p=player(id);World world=editable(p,null);LinkTemplateGui.open(p,world,this);return null;});
    }
    static World editable(Player p,UUID expected){
        if(!p.hasPermission("link.admin"))throw new EditorError("link.admin 권한이 필요합니다.");
        Visit visit=required(p);World world=p.getWorld();
        if(!world.getName().equals(visit.workspace())||(expected!=null&&!expected.equals(world.getUID())))throw new EditorError("설정을 연 편집 월드 안에서 사용하세요.");
        return world;
    }
    CompletionStage<Void> configure(UUID id,UUID worldId,java.util.function.Consumer<World> change){
        return exclusive(id,()->main(()->{World world=editable(player(id),worldId);change.accept(world);return world.getName();}).thenCompose(worlds::save));
    }
    public CompletionStage<Void> save(UUID id,String template,int version,boolean exit){
        LinkApi.requireName(template);positive(version);
        return exclusive(id,()->main(()->{
            Player player=player(id);Visit visit=required(player);
            if(!player.getWorld().getName().equals(visit.workspace()))throw new EditorError("편집 월드 안에서 저장하세요. /템플릿 입장 "+visit.workspace());
            return visit;
        }).thenCompose(visit->worlds.save(visit.workspace())
            .thenCompose(v->worlds.publish(visit.workspace(),template,version))
            .thenCompose(v->exit?returnHome(id,visit):CompletableFuture.completedFuture(null))));
    }
    public CompletionStage<Void> exit(UUID id){
        return exclusive(id,()->main(()->required(player(id))).thenCompose(visit->
            worlds.save(visit.workspace()).thenCompose(v->returnHome(id,visit))));
    }
    private CompletionStage<Void> returnHome(UUID id,Visit visit){
        return main(()->{
            Player player=player(id);
            if(!visit.equals(required(player)))throw new EditorError("편집 세션이 변경되었습니다.");
            World world=Bukkit.getWorld(visit.returnWorld());
            if(world==null)throw new EditorError("복귀 월드가 로드되어 있지 않습니다. 월드를 불러온 뒤 다시 나가세요. 작업은 저장되었습니다.");
            if(!player.teleport(new Location(world,visit.x(),visit.y(),visit.z(),visit.yaw(),visit.pitch())))
                throw new EditorError("다른 플러그인이 복귀를 취소했습니다. 작업은 저장되었으며 다시 나갈 수 있습니다.");
            player.getPersistentDataContainer().remove(KEY);return null;
        });
    }
    static void positive(int version){if(version<1)throw new EditorError("버전은 1 이상의 정수여야 합니다.");}
}
