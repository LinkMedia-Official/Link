package io.linkmc.server;

import io.linkmc.api.LinkApi;
import net.minecraft.server.MinecraftServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Backend administration and interactive template entry point. */
final class LinkCommand extends Command {
    private final LinkApi api;
    private final LinkCompletions completions;
    LinkCommand(LinkApi api,LinkCompletions completions){
        super("link","Link 월드 관리","/링크 <템플릿|템플릿목록|월드목록|에디터|발행|생성|불러오기|저장|언로드|삭제>",List.of("링크"));
        this.api=api;this.completions=completions;setPermission("link.admin");
    }
    static String action(String value){return switch(value.toLowerCase(Locale.ROOT)){
        case "템플릿"->"template";case "템플릿목록"->"templates";case "월드목록"->"worlds";
        case "에디터"->"editor";case "발행"->"publish";case "생성"->"create";
        case "불러오기"->"load";case "저장"->"save";case "언로드"->"unload";case "삭제"->"delete";
        default->value.toLowerCase(Locale.ROOT);
    };}
    static void register(LinkApi api,LinkCompletions completions){
        var server=org.bukkit.Bukkit.getServer();
        if(server.getPluginManager().getPermission("link.admin")==null)
            server.getPluginManager().addPermission(new Permission("link.admin",PermissionDefault.OP));
        server.getCommandMap().register("link",new LinkCommand(api,completions));
        server.getCommandMap().register("link",new LinkTemplateCommand(api,completions));
    }
    @Override public boolean execute(CommandSender sender,String label,String[] args){
        if(!testPermission(sender))return true;
        try {
            if(args.length==0){sender.sendMessage(getUsage());sender.sendMessage(LinkTemplateCommand.HELP);return true;}
            String action=action(args[0]);
            if(action.equals("template"))return new LinkTemplateCommand(api,completions).execute(sender,label,Arrays.copyOfRange(args,1,args.length));
            CompletionStage<?> operation=switch(action){
                case "templates" -> {count(args,1);yield api.worlds().templates();}
                case "worlds" -> {count(args,1);yield api.worlds().loaded();}
                case "editor" -> {count(args,2);yield sender instanceof org.bukkit.entity.Player player
                    ?api.editors().create(player.getUniqueId(),args[1]):api.worlds().createEditor(args[1]);}
                case "publish" -> {count(args,4);yield api.worlds().publish(args[1],args[2],LinkTemplateCommand.version(args[3]));}
                case "create" -> {
                    count(args,5);String mode=args[4].toLowerCase(Locale.ROOT);
                    if(!Set.of("persistent","temporary","영구","임시").contains(mode))throw new IllegalArgumentException("저장 방식은 persistent/영구 또는 temporary/임시입니다.");
                    yield api.worlds().create(args[1],args[2],LinkTemplateCommand.version(args[3]),mode.equals("persistent")||mode.equals("영구"));
                }
                case "load" -> {count(args,2);yield api.worlds().load(args[1]);}
                case "save" -> {count(args,2);yield api.worlds().save(args[1]);}
                case "unload" -> {count(args,2);yield api.worlds().unload(args[1]);}
                case "delete" -> {count(args,2);yield api.worlds().delete(args[1]);}
                default -> throw new IllegalArgumentException(getUsage());
            };
            report(sender,operation,"Link 작업이 완료되었습니다.");
        }catch(IllegalArgumentException e){sender.sendMessage(e.getMessage().equals("Invalid Link identifier")?"이름은 영문·숫자·밑줄·하이픈 등 Link 식별자를 사용하세요. 한국어는 명령어에 지원됩니다.":e.getMessage());}
        return true;
    }
    static void report(CommandSender sender,CompletionStage<?> operation,String success){
        operation.whenComplete((result,error)->MinecraftServer.getServer().execute(()->{
            if(error!=null){
                String message=friendlyError(error);
                if(message!=null)sender.sendMessage(message);
                else{
                    MinecraftServer.LOGGER.error("Link administrative operation failed",error);
                    sender.sendMessage("작업에 실패했습니다. 월드 상태·템플릿 이름·버전을 확인하세요. 이미 발행한 버전은 덮어쓸 수 없습니다. 자세한 원인은 서버 로그를 확인하세요.");
                }
            }else{
                sender.sendMessage(success);
                if(result instanceof LinkApi.WorldInfo world)sender.sendMessage("작업 월드: "+world.name());
                else if(result instanceof List<?> list){
                    if(list.isEmpty())sender.sendMessage("목록이 비어 있습니다.");
                    for(Object entry:list)sender.sendMessage(entry instanceof LinkApi.WorldInfo world
                        ?world.name()+" | "+(world.persistent()?"영구":"임시")+" | 플레이어 "+world.players()+"명":entry instanceof LinkApi.WarpGroup group
                        ?group.name()+" | 템플릿 "+group.template()+":"+group.version()+" | 채널당 "+group.policy().capacity()+"명 | 최대 "+group.policy().maxChannels()+"개 | "+(group.policy().networkWide()?"네트워크 전체":group.homeNode()):entry instanceof LinkApi.Channel channel?channel.world()+" | 서버 "+channel.node()+" | 입장 "+channel.occupied()+"명 + 예약 "+channel.reserved()+"명 / "+channel.capacity()+"명":entry.toString());
                }
            }
        }));
    }
    static String friendlyError(Throwable error){
        Throwable cause=error;
        while((cause instanceof java.util.concurrent.CompletionException||cause instanceof java.util.concurrent.ExecutionException)&&cause.getCause()!=null)cause=cause.getCause();
        if(cause instanceof LinkEditors.EditorError)return cause.getMessage();
        if(cause instanceof io.linkmc.core.ConflictException conflict)return switch(conflict.kind()){
            case WORLD_EXISTS->"이미 등록된 월드입니다: "+conflict.world()+". 기존 작업은 /템플릿 입장 "+conflict.world()+" 으로 이어가세요. 새 작업은 다른 이름으로 생성하세요.";
            case WORLD_DELETED->"삭제된 월드 이름은 재사용할 수 없습니다: "+conflict.world()+". /템플릿 생성 <새이름>을 사용하세요.";
            case GENERAL->null;
        };
        return null;
    }
    private static void count(String[] args,int expected){if(args.length!=expected)throw new IllegalArgumentException("인자 개수가 올바르지 않습니다. /링크 또는 /템플릿으로 사용법을 확인하세요.");}
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){
        return testPermissionSilent(sender)?completions.admin(args):List.of();
    }
}
