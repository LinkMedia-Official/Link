package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

final class LinkTemplateCommand extends Command {
    static final String HELP="/템플릿 생성 <작업월드> | 편집 <이름> <버전> | 입장 <작업월드> | 저장 <이름> <새버전> | 나가기 | 저장후나가기 <이름> <새버전> | 설정 | 목록";
    private final LinkApi api;
    private final LinkCompletions completions;
    LinkTemplateCommand(LinkApi api,LinkCompletions completions){
        super("template","템플릿 편집",HELP,List.of("템플릿"));
        this.api=api;this.completions=completions;setPermission("link.admin");
    }
    static String action(String value){return switch(value.toLowerCase(Locale.ROOT)){
        case "설정","gui"->"settings";case "생성"->"new";case "편집"->"edit";case "입장"->"enter";case "저장"->"save";
        case "나가기"->"exit";case "저장후나가기"->"saveexit";case "목록"->"list";
        default->value.toLowerCase(Locale.ROOT);
    };}
    @Override public boolean execute(CommandSender sender,String label,String[] args){
        if(!testPermission(sender))return true;
        try{
            if(args.length==0){sender.sendMessage(HELP);return true;}
            String action=action(args[0]);
            if(action.equals("list")){
                count(args,1);LinkCommand.report(sender,api.worlds().templates(),"템플릿 목록");return true;
            }
            if(!(sender instanceof Player player)){sender.sendMessage("편집 명령어는 플레이어만 사용할 수 있습니다.");return true;}
            if(action.equals("settings")){count(args,1);api.editors().openSettings(player.getUniqueId()).whenComplete((v,e)->{if(e!=null)LinkCommand.report(sender,java.util.concurrent.CompletableFuture.failedFuture(e),"");});return true;}
            CompletionStage<?> operation=switch(action){
                case "new"->{count(args,2);yield api.editors().create(player.getUniqueId(),args[1]);}
                case "edit"->{count(args,3);yield api.editors().edit(player.getUniqueId(),args[1],version(args[2]));}
                case "enter"->{count(args,2);yield api.editors().enter(player.getUniqueId(),args[1]);}
                case "save","saveexit"->{count(args,3);yield api.editors().save(player.getUniqueId(),args[1],version(args[2]),action.equals("saveexit"));}
                case "exit"->{count(args,1);yield api.editors().exit(player.getUniqueId());}
                default->throw new IllegalArgumentException(HELP);
            };
            String success=switch(action){
                case "new","edit","enter"->"편집 월드에 입장했습니다. /템플릿 저장 <이름> <새버전> 또는 /템플릿 나가기를 사용하세요.";
                case "exit"->"작업 월드를 저장하고 원래 위치로 돌아왔습니다. 템플릿 발행은 /템플릿 저장으로 진행하세요.";
                case "saveexit"->"템플릿을 발행하고 원래 위치로 돌아왔습니다.";
                default->"템플릿을 발행했습니다. /템플릿 나가기로 돌아갈 수 있습니다.";
            };
            LinkCommand.report(sender,operation,success);
        }catch(IllegalArgumentException error){sender.sendMessage(error.getMessage());}
        return true;
    }
    static int version(String value){
        try{int version=Integer.parseInt(value);LinkEditors.positive(version);return version;}
        catch(NumberFormatException error){throw new IllegalArgumentException("버전은 1 이상의 정수여야 합니다.");}
    }
    private static void count(String[] args,int expected){if(args.length!=expected)throw new IllegalArgumentException(HELP);}
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){
        return testPermissionSilent(sender)?completions.template(args):List.of();
    }
}
