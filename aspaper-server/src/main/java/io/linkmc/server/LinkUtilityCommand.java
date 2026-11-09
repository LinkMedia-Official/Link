package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.*;

final class LinkUtilityCommand extends Command {
    private final LinkApi.Utilities api;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Throwable> errors;
    LinkUtilityCommand(String name,LinkApi.Utilities api,Consumer<Runnable> dispatch,Consumer<Throwable> errors){
        super(name,"Link 플레이어 유틸리티",usage(name),aliases(name));this.api=api;this.dispatch=dispatch;this.errors=errors;
        setPermission("link."+(name.endsWith("speed")?"speed":name));
    }
    static List<String> aliases(String name){return switch(name){case "speed"->List.of("속도");case "flyspeed"->List.of("비행속도");case "walkspeed"->List.of("걷기속도","이동속도");case "fly"->List.of("비행");case "heal"->List.of("회복");default->List.of("배고픔","허기");};}
    static String usage(String name){return switch(name){case "speed"->"/속도 <0~10|초기화> [걷기|비행] | /speed <0..10|reset> [walk|fly]";case "flyspeed"->"/비행속도 <0~10|초기화> | /flyspeed <0..10|reset>";case "walkspeed"->"/걷기속도 <0~10|초기화> | /walkspeed <0..10|reset>";case "fly"->"/비행 [켜기|끄기] | /fly [on|off]";case "heal"->"/회복 | /heal";default->"/배고픔 | /feed";};}
    static void register(LinkApi.Utilities api){
        var server=org.bukkit.Bukkit.getServer();
        for(String permission:List.of("speed","fly","heal","feed"))if(server.getPluginManager().getPermission("link."+permission)==null)server.getPluginManager().addPermission(new Permission("link."+permission,PermissionDefault.OP));
        for(String name:List.of("speed","flyspeed","walkspeed","fly","heal","feed"))server.getCommandMap().register("link",new LinkUtilityCommand(name,api,task->net.minecraft.server.MinecraftServer.getServer().execute(task),e->net.minecraft.server.MinecraftServer.LOGGER.error("Link utility failed",e)));
    }
    static double level(String value){if(Set.of("reset","초기화").contains(value.toLowerCase(Locale.ROOT)))return 1;try{double n=Double.parseDouble(value);LinkUtilities.speedValue(n,false);return n;}catch(NumberFormatException e){throw new LinkUtilities.Rejected("속도는 0~10 사이의 숫자 또는 초기화/reset을 입력하세요.");}}
    static LinkApi.MovementMode mode(String s){return switch(s.toLowerCase(Locale.ROOT)){case "fly","비행"->LinkApi.MovementMode.FLY;case "walk","걷기","이동"->LinkApi.MovementMode.WALK;default->throw new LinkUtilities.Rejected("이동 종류는 걷기/walk 또는 비행/fly입니다.");};}
    @Override public boolean execute(CommandSender sender,String label,String[] args){
        if(!testPermission(sender))return true;
        if(!(sender instanceof Player p)){sender.sendMessage("플레이어만 사용할 수 있습니다.");return true;}
        try{
            String name=getName();CompletionStage<Void> operation;
            if(name.endsWith("speed")){
                if(args.length<1||args.length>(name.equals("speed")?2:1))throw new LinkUtilities.Rejected(getUsage());
                var type=name.equals("flyspeed")?LinkApi.MovementMode.FLY:name.equals("walkspeed")?LinkApi.MovementMode.WALK:args.length==2?mode(args[1]):LinkApi.MovementMode.AUTO;
                operation=api.speed(p.getUniqueId(),level(args[0]),type);
            }else if(name.equals("fly")){
                if(args.length>1)throw new LinkUtilities.Rejected(getUsage());
                Boolean enabled=args.length==0?null:switch(args[0].toLowerCase(Locale.ROOT)){case "on","켜기"->true;case "off","끄기"->false;default->throw new LinkUtilities.Rejected(getUsage());};
                operation=api.flight(p.getUniqueId(),enabled);
            }else{if(args.length!=0)throw new LinkUtilities.Rejected(getUsage());operation=name.equals("heal")?api.heal(p.getUniqueId()):api.feed(p.getUniqueId());}
            operation.whenComplete((v,error)->dispatch.accept(()->{
                if(error==null)p.sendMessage("적용하고 저장했습니다: "+label);
                else{Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof LinkUtilities.Rejected)p.sendMessage(cause.getMessage());else{errors.accept(error);p.sendMessage("유틸리티 처리에 실패했습니다. 서버 로그를 확인하세요.");}}
            }));
        }catch(LinkUtilities.Rejected e){p.sendMessage(e.getMessage());}
        return true;
    }
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){
        if(!testPermissionSilent(sender)||!(sender instanceof Player))return List.of();List<String> values=List.of();
        if(args.length==1){if(getName().endsWith("speed"))values=List.of("0","1","2","3","4","5","6","7","8","9","10","reset","초기화");else if(getName().equals("fly"))values=List.of("on","off","켜기","끄기");}
        else if(args.length==2&&getName().equals("speed"))values=List.of("walk","fly","걷기","비행");
        if(args.length==0)return List.of();String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return values.stream().filter(v->v.startsWith(prefix)).toList();
    }
}
