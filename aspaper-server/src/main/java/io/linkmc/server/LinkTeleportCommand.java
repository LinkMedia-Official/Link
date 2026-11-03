package io.linkmc.server;

import io.linkmc.api.LinkApi;
import io.linkmc.core.TeleportStore;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.*;
import org.bukkit.persistence.PersistentDataType;

final class LinkTeleportCommand extends Command {
    interface Suggestions {List<String> get(UUID player,String action,String prefix);}
    private final LinkApi.Teleports service;
    private final boolean journal;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Throwable> errors;
    private final Suggestions suggestions;
    LinkTeleportCommand(String name,LinkApi.Teleports service,boolean journal,Consumer<Runnable> dispatch,Consumer<Throwable> errors,Suggestions suggestions){
        super(name,"동의 후 플레이어에게 이동",switch(name){case "tpa"->"/티피요청 <유저>";case "tpahere"->"/티피여기 <유저>";case "tpaccept"->"/티피수락 [요청자]";case "tpdeny"->"/티피거절 [요청자]";default->"/티피취소";},List.of(switch(name){case "tpa"->"티피요청";case "tpahere"->"티피여기";case "tpaccept"->"티피수락";case "tpdeny"->"티피거절";default->"티피취소";}));
        this.service=service;this.journal=journal;this.dispatch=dispatch;this.errors=errors;this.suggestions=suggestions;setPermission("link.tpa");
    }
    static void register(LinkApi.Teleports service,LinkTeleportRuntime runtime){
        var server=org.bukkit.Bukkit.getServer();
        if(server.getPluginManager().getPermission("link.tpa")==null)server.getPluginManager().addPermission(new Permission("link.tpa",PermissionDefault.TRUE));
        for(String name:List.of("tpa","tpahere","tpaccept","tpdeny","tpacancel"))server.getCommandMap().register("link",new LinkTeleportCommand(name,service,NativeJournal.enabled(),task->net.minecraft.server.MinecraftServer.getServer().execute(task),error->net.minecraft.server.MinecraftServer.LOGGER.error("Teleport request operation failed",error),runtime::suggest));
    }
    @Override public boolean execute(CommandSender sender,String label,String[] args){
        if(!testPermission(sender))return true;
        if(!(sender instanceof Player player)){sender.sendMessage("플레이어만 사용할 수 있습니다.");return true;}
        if(!journal){sender.sendMessage("티피 요청은 journal 모드에서 사용할 수 있습니다.");return true;}
        if(player.getPersistentDataContainer().has(LinkEditors.KEY,PersistentDataType.STRING)){sender.sendMessage("/템플릿 나가기 후 티피 요청을 사용하세요.");return true;}
        boolean request=getName().equals("tpa")||getName().equals("tpahere");
        if((request&&args.length!=1)||(!request&&args.length>1)||(getName().equals("tpacancel")&&args.length!=0)){sender.sendMessage(getUsage());return true;}
        CompletionStage<?> operation=switch(getName()){
            case "tpa","tpahere"->service.request(player.getUniqueId(),args[0],getName().equals("tpahere"));
            case "tpaccept","tpdeny"->service.respond(player.getUniqueId(),args.length==0?null:args[0],getName().equals("tpaccept"));
            default->service.cancel(player.getUniqueId());
        };
        operation.whenComplete((result,error)->dispatch.accept(()->{
            if(error!=null){Throwable cause=error;while(cause instanceof java.util.concurrent.CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                if(cause instanceof TeleportStore.Rejected)player.sendMessage(cause.getMessage());else{errors.accept(error);player.sendMessage("티피 요청 처리에 실패했습니다. 잠시 후 다시 시도하세요.");}
            }else if(request)player.sendMessage("티피 요청을 보냈습니다. 상대의 수락이 필요하며 60초 후 만료됩니다.");
            else if(getName().equals("tpaccept"))player.sendMessage("티피 요청을 수락했습니다. 이동을 준비합니다.");
            else if(getName().equals("tpdeny"))player.sendMessage("티피 요청을 거절했습니다.");
            else player.sendMessage(Boolean.TRUE.equals(result)?"티피 요청을 취소했습니다.":"취소할 대기 요청이 없습니다.");
        }));return true;
    }
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){
        if(!journal||!testPermissionSilent(sender)||!(sender instanceof Player player)||args.length!=1||getName().equals("tpacancel"))return List.of();
        return suggestions.get(player.getUniqueId(),getName(),args[0]).stream().filter(name->!name.equalsIgnoreCase(player.getName())).toList();
    }
}
