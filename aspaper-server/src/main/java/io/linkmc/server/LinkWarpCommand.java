package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** SQL-backed administration. The proxy never registers these commands. */
final class LinkWarpCommand extends Command {
    private final LinkApi.Network network;
    private final String node;
    private final int capacity;
    private final boolean journal;
    private final LinkCompletions completions;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Throwable> errors;
    LinkWarpCommand(String name,LinkApi.Network network,String node,int capacity,boolean journal,LinkCompletions completions,Consumer<Runnable> dispatch,Consumer<Throwable> errors){
        super(name,"Link 워프 관리",switch(name){case "setwarp"->"/워프설정 <이름>";case "delwarp"->"/워프삭제 <이름>";default->"/워프목록 [페이지]";},
            List.of(switch(name){case "setwarp"->"워프설정";case "delwarp"->"워프삭제";default->"워프목록";}));
        this.network=network;this.node=node;this.capacity=capacity;this.journal=journal;this.completions=completions;this.dispatch=dispatch;this.errors=errors;
        setPermission(name.equals("warps")?"link.warp":"link.admin");
    }
    static void register(LinkApi.Network network,String node,int capacity,LinkCompletions completions){
        for(String name:List.of("setwarp","delwarp","warps"))org.bukkit.Bukkit.getServer().getCommandMap().register("link",
            new LinkWarpCommand(name,network,node,capacity,NativeJournal.enabled(),completions,
                task->net.minecraft.server.MinecraftServer.getServer().execute(task),
                error->net.minecraft.server.MinecraftServer.LOGGER.error("Link warp administration failed",error)));
    }
    @Override public boolean execute(CommandSender sender,String label,String[] args){
        if(!testPermission(sender))return true;
        if(!journal){sender.sendMessage("워프 관리는 journal 모드에서 사용할 수 있습니다.");return true;}
        try{
            if(getName().equals("warps")){
                if(args.length>1)throw new IllegalArgumentException(getUsage());
                int page=args.length==0?1:Integer.parseInt(args[0]);
                if(page<1)throw new IllegalArgumentException("페이지는 1 이상이어야 합니다.");
                reply(sender,network.warps(),names->{
                    int pages=Math.max(1,(names.size()+9)/10);
                    if(page>pages){sender.sendMessage("마지막 페이지는 "+pages+"입니다.");return;}
                    sender.sendMessage("워프 목록 "+page+"/"+pages+" (총 "+names.size()+"개)");
                    if(names.isEmpty())sender.sendMessage("등록된 워프가 없습니다.");
                    else for(String name:names.subList((page-1)*10,Math.min(page*10,names.size())))sender.sendMessage("/워프 "+name);
                });return true;
            }
            if(args.length!=1)throw new IllegalArgumentException(getUsage());
            LinkApi.requireName(args[0]);String name=args[0];
            if(getName().equals("delwarp")){
                reply(sender,network.deleteWarp(name),deleted->sender.sendMessage(deleted?"워프를 DB에서 삭제했습니다: "+name:"등록되지 않은 워프입니다: "+name));return true;
            }
            if(!(sender instanceof Player player)){sender.sendMessage("워프설정은 플레이어만 사용할 수 있습니다.");return true;}
            if(player.getPersistentDataContainer().has(LinkEditors.KEY,PersistentDataType.STRING)){
                sender.sendMessage("편집 중에는 워프를 설정할 수 없습니다. /템플릿 나가기 후 실제 사용할 월드에서 설정하세요.");return true;
            }
            var location=player.getLocation();
            var destination=new LinkApi.Destination(node,location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch());
            reply(sender,network.setWarp(name,destination,capacity),ignored->sender.sendMessage("워프 위치를 DB에 저장했습니다: "+name+" (같은 이름이 있으면 갱신됩니다.)"));
        }catch(NumberFormatException error){sender.sendMessage("페이지는 1 이상의 정수여야 합니다.");}
        catch(IllegalArgumentException error){sender.sendMessage("Invalid Link identifier".equals(error.getMessage())?"워프 이름은 영문·숫자·밑줄·하이픈 등 Link 식별자를 사용하세요.":error.getMessage());}
        return true;
    }
    private <T> void reply(CommandSender sender,CompletionStage<T> operation,Consumer<T> success){
        operation.whenComplete((value,error)->dispatch.accept(()->{
            if(error!=null){errors.accept(error);sender.sendMessage("워프 작업에 실패했습니다. 서버 로그를 확인하세요.");}
            else success.accept(value);
        }));
    }
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){
        if(!journal||!testPermissionSilent(sender)||args.length!=1)return List.of();
        if(getName().equals("warps"))return completions.warpPages(args[0]);
        return getName().equals("setwarp")?completions.fixedWarps(args[0]):completions.warps(args[0]);
    }
}
