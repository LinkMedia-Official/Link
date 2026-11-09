package io.linkmc.server;

import io.linkmc.core.Database;
import java.util.*;
import java.util.function.Supplier;

/** Immutable catalog refreshed off the tick thread. TAB never waits for database I/O. */
final class LinkCompletions {
    static final List<String> COMMANDS=List.of("template","templates","worlds","editor","publish","create","load","save","unload","delete","템플릿","템플릿목록","월드목록","에디터","발행","생성","불러오기","저장","언로드","삭제");
    record Catalog(Map<String,List<Integer>> templates,List<String> stored,List<String> warps,List<String> groups){Catalog(Map<String,List<Integer>> templates,List<String> stored,List<String> warps){this(templates,stored,warps,List.of());}}
    private volatile Catalog catalog=new Catalog(Map.of(),List.of(),List.of());
    private final Supplier<List<String>> loaded;
    private final Supplier<List<String>> editableLoaded;
    private final String defaultTemplate;
    LinkCompletions(Supplier<List<String>> loaded,String defaultTemplate){this(loaded,loaded,defaultTemplate);}
    LinkCompletions(Supplier<List<String>> loaded,Supplier<List<String>> editableLoaded,String defaultTemplate){this.loaded=loaded;this.editableLoaded=editableLoaded;this.defaultTemplate=defaultTemplate;}
    void replace(Catalog value){
        Map<String,List<Integer>> templates=new TreeMap<>();value.templates().forEach((name,versions)->templates.put(name,List.copyOf(versions)));
        catalog=new Catalog(Map.copyOf(templates),List.copyOf(value.stored()),List.copyOf(value.warps()),List.copyOf(value.groups()));
    }
    void refresh(Database db,String node)throws Exception{
        replace(db.transaction(c->{
            Map<String,List<Integer>> templates=new TreeMap<>();List<String> stored=new ArrayList<>(),warps=new ArrayList<>();
            try(var s=Database.sql(c,"SELECT name,version FROM link_templates ORDER BY name,version");var r=s.executeQuery()){
                while(r.next())templates.computeIfAbsent(r.getString(1),key->new ArrayList<>()).add(r.getInt(2));
            }
            try(var s=Database.sql(c,"SELECT name FROM link_warps UNION SELECT name FROM link_warp_groups ORDER BY name");var r=s.executeQuery()){while(r.next())warps.add(r.getString(1));}
            String prefix="native:"+node+"/catalog/";
            try(var s=Database.sql(c,"SELECT id,payload FROM link_state WHERE starts_with(id,?)",prefix);var r=s.executeQuery()){
                while(r.next()){byte[] state=r.getBytes(2);if(state.length==1&&state[0]==1)stored.add(r.getString(1).substring(prefix.length()));}
            }
            prefix="world:"+node+"/";
            try(var s=Database.sql(c,"SELECT id FROM link_state WHERE starts_with(id,?) AND OCTET_LENGTH(payload)>0",prefix);var r=s.executeQuery()){
                while(r.next())stored.add(r.getString(1).substring(prefix.length()));
            }
            var groups=new ArrayList<String>();try(var s=Database.sql(c,"SELECT name FROM link_warp_groups ORDER BY name");var r=s.executeQuery()){while(r.next())groups.add(r.getString(1));}
            return new Catalog(templates,stored,warps,groups);
        }));
    }
    List<String> admin(String[] args){
        if(args.length==0)return List.of();
        Catalog current=catalog;Collection<String> candidates=List.of();
        String sub=LinkCommand.action(args[0]);
        if(sub.equals("template")&&args.length>1)return template(Arrays.copyOfRange(args,1,args.length));
        if(args.length==1)candidates=COMMANDS;
        else if(args.length==2){
            if(sub.equals("save"))candidates=editableLoaded.get();
            else if(Set.of("publish","unload").contains(sub))candidates=loaded.get();
            else if(Set.of("load","delete").contains(sub)){
                var names=new ArrayList<>(current.stored());names.removeAll(loaded.get());candidates=names;
            }
        }else if(args.length==3&&(sub.equals("create")||sub.equals("publish"))){
            var names=new TreeSet<>(current.templates().keySet());if(sub.equals("publish"))names.add(defaultTemplate);candidates=names;
        }else if(args.length==4&&(sub.equals("create")||sub.equals("publish"))){
            var versions=current.templates().getOrDefault(args[2],List.of());
            if(sub.equals("create"))candidates=versions.stream().map(String::valueOf).toList();
            else{int maximum=versions.stream().mapToInt(Integer::intValue).max().orElse(0);if(maximum<Integer.MAX_VALUE)candidates=List.of(String.valueOf(maximum+1));}
        }else if(args.length==5&&sub.equals("create"))candidates=List.of("persistent","temporary","영구","임시");
        return match(candidates,args[args.length-1]);
    }
    List<String> template(String[] args){
        if(args.length==0)return List.of();
        Catalog current=catalog;Collection<String> candidates=List.of();
        String sub=LinkTemplateCommand.action(args[0]);
        if(args.length==1)candidates=List.of("settings","gui","설정","new","edit","enter","save","exit","saveexit","list","생성","편집","입장","저장","나가기","저장후나가기","목록");
        else if(args.length==2){
            if(Set.of("edit","save","saveexit").contains(sub)){
                var names=new TreeSet<>(current.templates().keySet());if(!sub.equals("edit"))names.add(defaultTemplate);candidates=names;
            }else if(sub.equals("enter")){
                var names=new TreeSet<>(current.stored());names.addAll(editableLoaded.get());candidates=names;
            }
        }else if(args.length==3&&Set.of("edit","save","saveexit").contains(sub)){
            var versions=current.templates().getOrDefault(args[1],List.of());
            if(sub.equals("edit"))candidates=versions.stream().map(String::valueOf).toList();
            else{int maximum=versions.stream().mapToInt(Integer::intValue).max().orElse(0);if(maximum<Integer.MAX_VALUE)candidates=List.of(String.valueOf(maximum+1));}
        }
        return match(candidates,args[args.length-1]);
    }
    List<String> fixedWarps(String prefix){return warps(prefix).stream().filter(n->!catalog.groups().contains(n)).toList();}
    List<String> groups(String prefix){return match(catalog.groups(),prefix);}
    List<String> warpPages(String prefix){int pages=Math.max(1,(catalog.warps().size()+9)/10);return java.util.stream.IntStream.rangeClosed(1,pages).mapToObj(String::valueOf).filter(s->s.startsWith(prefix)).toList();}
    List<String> warps(String prefix){return match(catalog.warps(),prefix);}
    private static List<String> match(Collection<String> values,String prefix){
        String lower=prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value->value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }
}
