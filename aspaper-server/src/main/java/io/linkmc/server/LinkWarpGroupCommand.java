package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import org.bukkit.command.*;

final class LinkWarpGroupCommand extends Command {
    private final LinkApi api;private final LinkCompletions completions;private final String node;
    LinkWarpGroupCommand(LinkApi api,LinkCompletions completions,String node){super("warpgroup","템플릿 채널 워프 연결","/워프그룹 <워프이름> <템플릿> <버전> | /워프그룹 목록 | /워프그룹 채널 <그룹>",List.of("워프그룹"));this.api=api;this.completions=completions;this.node=node;setPermission("link.admin");}
    @Override public boolean execute(CommandSender sender,String label,String[] args){
        if(!testPermission(sender))return true;
        if(!NativeJournal.enabled()){sender.sendMessage("워프 그룹은 journal 모드에서 사용할 수 있습니다.");return true;}
        try{
            if(args.length==1&&(args[0].equals("목록")||args[0].equalsIgnoreCase("list"))){LinkCommand.report(sender,api.warpGroups().list(),"템플릿 워프 그룹");return true;}
            if(args.length==2&&(args[0].equals("채널")||args[0].equalsIgnoreCase("channels"))){LinkCommand.report(sender,api.warpGroups().channels(args[1]),"준비된 그룹 채널");return true;}
            if(args.length!=3){sender.sendMessage(getUsage());return true;}
            LinkApi.requireName(args[0]);LinkApi.requireName(args[1]);int version=LinkTemplateCommand.version(args[2]);
            LinkCommand.report(sender,api.warpGroups().bind(args[0],args[1],version,node),"템플릿의 채널 설정으로 워프 그룹을 연결했습니다. /워프 "+args[0]);
        }catch(IllegalArgumentException e){sender.sendMessage(getUsage());}return true;
    }
    @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){
        if(!testPermissionSilent(sender))return List.of();
        if(args.length==1)return java.util.stream.Stream.concat(List.of("list","목록","channels","채널").stream(),completions.groups(args[0]).stream()).filter(s->s.startsWith(args[0])).distinct().sorted().toList();
        if(args.length==2&&(args[0].equals("채널")||args[0].equalsIgnoreCase("channels")))return completions.groups(args[1]);
        if(args.length==2)return completions.template(new String[]{"edit",args[1]});
        if(args.length==3)return completions.template(new String[]{"edit",args[1],args[2]});return List.of();
    }
}
