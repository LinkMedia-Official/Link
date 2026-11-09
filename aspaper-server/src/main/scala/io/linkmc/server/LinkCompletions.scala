package io.linkmc.server

import io.linkmc.core.Database
import java.util.{ArrayList, Arrays, Collection, List, Locale, Map, Set, TreeMap, TreeSet}
import java.util.function.Supplier
import scala.util.Using

/** Immutable catalog refreshed off the tick thread. TAB never waits for database I/O. */
private[server] final class LinkCompletions private[server] (loaded: Supplier[List[String]], editableLoaded: Supplier[List[String]], defaultTemplate: String) {
  import LinkCompletions.{COMMANDS, Catalog, `match`}

  @volatile private var catalog: Catalog = new Catalog(Map.of[String, List[Integer]](), List.of[String](), List.of[String]())

  private[server] def this(loaded: Supplier[List[String]], defaultTemplate: String) = this(loaded, loaded, defaultTemplate)

  private[server] def replace(value: Catalog): Unit = {
    val templates: Map[String, List[Integer]] = new TreeMap[String, List[Integer]](); value.templates.forEach((name, versions) => { templates.put(name, List.copyOf(versions)); () })
    catalog = new Catalog(Map.copyOf(templates), List.copyOf(value.stored), List.copyOf(value.warps), List.copyOf(value.groups))
  }
  @throws[Exception]
  private[server] def refresh(db: Database, node: String): Unit = {
    replace(db.transaction[Catalog] { c =>
      val templates: Map[String, List[Integer]] = new TreeMap[String, List[Integer]](); val stored: List[String] = new ArrayList[String](); val warps: List[String] = new ArrayList[String]()
      Using.resource(Database.sql(c, "SELECT name,version FROM link_templates ORDER BY name,version")) { s => Using.resource(s.executeQuery()) { r =>
        while (r.next()) templates.computeIfAbsent(r.getString(1), key => new ArrayList[Integer]()).add(r.getInt(2))
      } }
      Using.resource(Database.sql(c, "SELECT name FROM link_warps UNION SELECT name FROM link_warp_groups ORDER BY name")) { s => Using.resource(s.executeQuery()) { r => while (r.next()) warps.add(r.getString(1)) } }
      var prefix = "native:" + node + "/catalog/"
      Using.resource(Database.sql(c, "SELECT id,payload FROM link_state WHERE starts_with(id,?)", prefix)) { s => Using.resource(s.executeQuery()) { r =>
        while (r.next()) { val state = r.getBytes(2); if (state.length == 1 && state(0) == 1) stored.add(r.getString(1).substring(prefix.length())) }
      } }
      prefix = "world:" + node + "/"
      Using.resource(Database.sql(c, "SELECT id FROM link_state WHERE starts_with(id,?) AND OCTET_LENGTH(payload)>0", prefix)) { s => Using.resource(s.executeQuery()) { r =>
        while (r.next()) stored.add(r.getString(1).substring(prefix.length()))
      } }
      val groups = new ArrayList[String](); Using.resource(Database.sql(c, "SELECT name FROM link_warp_groups ORDER BY name")) { s => Using.resource(s.executeQuery()) { r => while (r.next()) groups.add(r.getString(1)) } }
      new Catalog(templates, stored, warps, groups)
    })
  }
  private[server] def admin(args: Array[String]): List[String] = {
    if (args.length == 0) return List.of()
    val current = catalog; var candidates: Collection[String] = List.of()
    val sub = LinkCommand.action(args(0))
    if (sub.equals("template") && args.length > 1) return template(Arrays.copyOfRange(args, 1, args.length))
    if (args.length == 1) candidates = COMMANDS
    else if (args.length == 2) {
      if (sub.equals("save")) candidates = editableLoaded.get()
      else if (Set.of("publish", "unload").contains(sub)) candidates = loaded.get()
      else if (Set.of("load", "delete").contains(sub)) {
        val names = new ArrayList[String](current.stored); names.removeAll(loaded.get()); candidates = names
      }
    } else if (args.length == 3 && (sub.equals("create") || sub.equals("publish"))) {
      val names = new TreeSet[String](current.templates.keySet()); if (sub.equals("publish")) names.add(defaultTemplate); candidates = names
    } else if (args.length == 4 && (sub.equals("create") || sub.equals("publish"))) {
      val versions = current.templates.getOrDefault(args(2), List.of[Integer]())
      if (sub.equals("create")) candidates = versions.stream().map[String]((v: Integer) => String.valueOf(v: Object)).toList()
      else { val maximum = versions.stream().mapToInt((v: Integer) => v.intValue()).max().orElse(0); if (maximum < Integer.MAX_VALUE) candidates = List.of(String.valueOf(maximum + 1)) }
    } else if (args.length == 5 && sub.equals("create")) candidates = List.of("persistent", "temporary", "영구", "임시")
    `match`(candidates, args(args.length - 1))
  }
  private[server] def template(args: Array[String]): List[String] = {
    if (args.length == 0) return List.of()
    val current = catalog; var candidates: Collection[String] = List.of()
    val sub = LinkTemplateCommand.action(args(0))
    if (args.length == 1) candidates = List.of("settings", "gui", "설정", "new", "edit", "enter", "save", "exit", "saveexit", "list", "생성", "편집", "입장", "저장", "나가기", "저장후나가기", "목록")
    else if (args.length == 2) {
      if (Set.of("edit", "save", "saveexit").contains(sub)) {
        val names = new TreeSet[String](current.templates.keySet()); if (!sub.equals("edit")) names.add(defaultTemplate); candidates = names
      } else if (sub.equals("enter")) {
        val names = new TreeSet[String](current.stored); names.addAll(editableLoaded.get()); candidates = names
      }
    } else if (args.length == 3 && Set.of("edit", "save", "saveexit").contains(sub)) {
      val versions = current.templates.getOrDefault(args(1), List.of[Integer]())
      if (sub.equals("edit")) candidates = versions.stream().map[String]((v: Integer) => String.valueOf(v: Object)).toList()
      else { val maximum = versions.stream().mapToInt((v: Integer) => v.intValue()).max().orElse(0); if (maximum < Integer.MAX_VALUE) candidates = List.of(String.valueOf(maximum + 1)) }
    }
    `match`(candidates, args(args.length - 1))
  }
  private[server] def fixedWarps(prefix: String): List[String] = warps(prefix).stream().filter(n => !catalog.groups.contains(n)).toList()
  private[server] def groups(prefix: String): List[String] = `match`(catalog.groups, prefix)
  private[server] def warpPages(prefix: String): List[String] = { val pages = Math.max(1, (catalog.warps.size() + 9) / 10); java.util.stream.IntStream.rangeClosed(1, pages).mapToObj[String]((i: Int) => String.valueOf(i)).filter(s => s.startsWith(prefix)).toList() }
  private[server] def warps(prefix: String): List[String] = `match`(catalog.warps, prefix)
}

object LinkCompletions {
  private[server] val COMMANDS: List[String] = List.of("template", "templates", "worlds", "editor", "publish", "create", "load", "save", "unload", "delete", "템플릿", "템플릿목록", "월드목록", "에디터", "발행", "생성", "불러오기", "저장", "언로드", "삭제")

  final case class Catalog(templates: Map[String, List[Integer]], stored: List[String], warps: List[String], groups: List[String]) {
    def this(templates: Map[String, List[Integer]], stored: List[String], warps: List[String]) = this(templates, stored, warps, List.of())
  }

  private def `match`(values: Collection[String], prefix: String): List[String] = {
    val lower = prefix.toLowerCase(Locale.ROOT)
    values.stream().filter(value => value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList()
  }
}
