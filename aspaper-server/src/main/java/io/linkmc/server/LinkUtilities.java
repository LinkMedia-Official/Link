package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;

final class LinkUtilities implements LinkApi.Utilities {
    static final NamespacedKey FLIGHT=new NamespacedKey("link","utility-flight");
    static final class Rejected extends IllegalArgumentException {Rejected(String message){super(message);}}
    private final Consumer<Runnable> dispatch;
    private final Function<UUID,Player> players;
    private final Predicate<UUID> moving;
    private final boolean journal;
    LinkUtilities(){this(task->net.minecraft.server.MinecraftServer.getServer().execute(task),Bukkit::getPlayer,NativeJournal::moving,NativeJournal.enabled());}
    LinkUtilities(Consumer<Runnable> dispatch,Function<UUID,Player> players,Predicate<UUID> moving,boolean journal){this.dispatch=dispatch;this.players=players;this.moving=moving;this.journal=journal;}
    private CompletionStage<Void> run(UUID id,Consumer<Player> action){
        var future=new CompletableFuture<Void>();
        if(!journal)return CompletableFuture.failedFuture(new Rejected("유틸리티는 journal 모드에서 사용할 수 있습니다."));
        if(!NativeJournal.watch(future))return future;
        try{dispatch.accept(()->NativeJournal.run(()->{
            try{
                Player p=players.apply(id);
                if(p==null||!p.isOnline()||p.isDead())throw new Rejected("살아 있는 접속 플레이어만 사용할 수 있습니다.");
                if(moving.test(id))throw new Rejected("서버 이동 중에는 사용할 수 없습니다.");
                action.accept(p);NativeJournal.complete(future,null);
            }catch(Rejected e){future.completeExceptionally(e);}
            catch(RuntimeException e){future.completeExceptionally(e);throw e;}
        }));}catch(RuntimeException e){future.completeExceptionally(e);}
        return future;
    }
    static float speedValue(double level,boolean fly){
        if(!Double.isFinite(level)||level<0||level>10)throw new Rejected("속도는 0~10 사이의 숫자 또는 초기화/reset을 입력하세요.");
        double base=fly?0.1:0.2;
        return (float)(level<=1?level*base:base+(level-1)*(1-base)/9);
    }
    public CompletionStage<Void> speed(UUID id,double level,LinkApi.MovementMode mode){
        Objects.requireNonNull(mode);speedValue(level,false);
        return run(id,p->{boolean fly=mode==LinkApi.MovementMode.FLY||(mode==LinkApi.MovementMode.AUTO&&p.isFlying());if(fly)p.setFlySpeed(speedValue(level,true));else p.setWalkSpeed(speedValue(level,false));});
    }
    public CompletionStage<Void> flight(UUID id,Boolean enabled){return run(id,p->{
        if(p.getGameMode()==GameMode.SPECTATOR)throw new Rejected("관전자 모드에서는 비행을 변경할 수 없습니다.");
        boolean next=enabled!=null?enabled:!p.getAllowFlight();
        if(!next&&p.getGameMode()==GameMode.CREATIVE)throw new Rejected("크리에이티브 모드의 비행 권한은 끌 수 없습니다.");
        if(next){p.setAllowFlight(true);p.setFlying(true);}else{p.setFlying(false);p.setAllowFlight(false);p.setFallDistance(0);}
        p.getPersistentDataContainer().set(FLIGHT,org.bukkit.persistence.PersistentDataType.BYTE,(byte)(next?1:0));
    });}
    /** Vanilla game-mode loading resets survival flight; restore only Link's explicit preference. */
    static void restoreFlight(Player p){
        if(p.getGameMode()!=GameMode.SURVIVAL&&p.getGameMode()!=GameMode.ADVENTURE)return;
        Byte value=p.getPersistentDataContainer().get(FLIGHT,org.bukkit.persistence.PersistentDataType.BYTE);
        if(value==null)return;
        if(value==1){p.setAllowFlight(true);p.setFlying(true);}
        else if(value==0){p.setFlying(false);p.setAllowFlight(false);}
    }
    public CompletionStage<Void> heal(UUID id){return run(id,p->{var max=p.getAttribute(Attribute.MAX_HEALTH);if(max==null)throw new Rejected("최대 체력을 확인할 수 없습니다.");p.setHealth(max.getValue());p.setFireTicks(0);});}
    public CompletionStage<Void> feed(UUID id){return run(id,p->{p.setFoodLevel(20);p.setSaturation(20);p.setExhaustion(0);});}
}
