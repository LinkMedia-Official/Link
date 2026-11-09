package io.linkmc.server

import io.linkmc.api.LinkApi
import net.minecraft.server.MinecraftServer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import java.util.{Arrays, List, Locale, Set}
import java.util.concurrent.CompletionStage

/** Backend administration and interactive template entry point. */
private[server] final class LinkCommand private[server] (api: LinkApi, completions: LinkCompletions)
    extends Command("link", "Link 월드 관리", "/링크 <템플릿|템플릿목록|월드목록|에디터|발행|생성|불러오기|저장|언로드|삭제>", List.of("링크")) {
  setPermission("link.admin")

  override def execute(sender: CommandSender, label: String, args: Array[String]): Boolean = {
    if (!testPermission(sender)) return true
    try {
      if (args.length == 0) { sender.sendMessage(getUsage()); sender.sendMessage(LinkTemplateCommand.HELP); return true }
      val action = LinkCommand.action(args(0))
      if (action.equals("template")) return new LinkTemplateCommand(api, completions).execute(sender, label, Arrays.copyOfRange(args, 1, args.length))
      val operation: CompletionStage[?] = action match {
        case "templates" => LinkCommand.count(args, 1); api.worlds().templates()
        case "worlds" => LinkCommand.count(args, 1); api.worlds().loaded()
        case "editor" => LinkCommand.count(args, 2); sender match {
            case player: org.bukkit.entity.Player => api.editors().create(player.getUniqueId(), args(1))
            case _ => api.worlds().createEditor(args(1))
          }
        case "publish" => LinkCommand.count(args, 4); api.worlds().publish(args(1), args(2), LinkTemplateCommand.version(args(3)))
        case "create" =>
          LinkCommand.count(args, 5); val mode = args(4).toLowerCase(Locale.ROOT)
          if (!Set.of("persistent", "temporary", "영구", "임시").contains(mode)) throw new IllegalArgumentException("저장 방식은 persistent/영구 또는 temporary/임시입니다.")
          api.worlds().create(args(1), args(2), LinkTemplateCommand.version(args(3)), mode.equals("persistent") || mode.equals("영구"))
        case "load" => LinkCommand.count(args, 2); api.worlds().load(args(1))
        case "save" => LinkCommand.count(args, 2); api.worlds().save(args(1))
        case "unload" => LinkCommand.count(args, 2); api.worlds().unload(args(1))
        case "delete" => LinkCommand.count(args, 2); api.worlds().delete(args(1))
        case _ => throw new IllegalArgumentException(getUsage())
      }
      LinkCommand.report(sender, operation, "Link 작업이 완료되었습니다.")
    } catch { case e: IllegalArgumentException => sender.sendMessage(if (e.getMessage().equals("Invalid Link identifier")) "이름은 영문·숫자·밑줄·하이픈 등 Link 식별자를 사용하세요. 한국어는 명령어에 지원됩니다." else e.getMessage()) }
    true
  }
  override def tabComplete(sender: CommandSender, alias: String, args: Array[String]): List[String] =
    if (testPermissionSilent(sender)) completions.admin(args) else List.of()
}

object LinkCommand {
  private[server] def action(value: String): String = value.toLowerCase(Locale.ROOT) match {
    case "템플릿" => "template"; case "템플릿목록" => "templates"; case "월드목록" => "worlds"
    case "에디터" => "editor"; case "발행" => "publish"; case "생성" => "create"
    case "불러오기" => "load"; case "저장" => "save"; case "언로드" => "unload"; case "삭제" => "delete"
    case _ => value.toLowerCase(Locale.ROOT)
  }
  // No static forwarder is emitted for this name (Command.register(CommandMap) exists on the class); Scala callers use the object.
  def register(api: LinkApi, completions: LinkCompletions): Unit = {
    val server = org.bukkit.Bukkit.getServer()
    if (server.getPluginManager().getPermission("link.admin") == null)
      server.getPluginManager().addPermission(new Permission("link.admin", PermissionDefault.OP))
    server.getCommandMap().register("link", new LinkCommand(api, completions))
    server.getCommandMap().register("link", new LinkTemplateCommand(api, completions))
  }
  private[server] def report(sender: CommandSender, operation: CompletionStage[?], success: String): Unit = {
    // Erasure-only cast: lets the lambda see the wildcard result as AnyRef (no runtime check).
    operation.asInstanceOf[CompletionStage[AnyRef]].whenComplete((result: AnyRef, error: Throwable) => MinecraftServer.getServer().execute(() => {
      if (error != null) {
        val message = friendlyError(error)
        if (message != null) sender.sendMessage(message)
        else {
          MinecraftServer.LOGGER.error("Link administrative operation failed", error)
          sender.sendMessage("작업에 실패했습니다. 월드 상태·템플릿 이름·버전을 확인하세요. 이미 발행한 버전은 덮어쓸 수 없습니다. 자세한 원인은 서버 로그를 확인하세요.")
        }
      } else {
        sender.sendMessage(success)
        result match {
          case world: LinkApi.WorldInfo => sender.sendMessage("작업 월드: " + world.name())
          case list: java.util.List[?] =>
            if (list.isEmpty()) sender.sendMessage("목록이 비어 있습니다.")
            val it = list.iterator()
            while (it.hasNext()) {
              val entry: AnyRef = it.next().asInstanceOf[AnyRef]
              sender.sendMessage(entry match {
                case world: LinkApi.WorldInfo => world.name() + " | " + (if (world.persistent()) "영구" else "임시") + " | 플레이어 " + world.players() + "명"
                case group: LinkApi.WarpGroup => group.name() + " | 템플릿 " + group.template() + ":" + group.version() + " | 채널당 " + group.policy().capacity() + "명 | 최대 " + group.policy().maxChannels() + "개 | " + (if (group.policy().networkWide()) "네트워크 전체" else group.homeNode())
                case channel: LinkApi.Channel => channel.world() + " | 서버 " + channel.node() + " | 입장 " + channel.occupied() + "명 + 예약 " + channel.reserved() + "명 / " + channel.capacity() + "명"
                case _ => entry.toString()
              })
            }
          case _ =>
        }
      }
    }))
  }
  // Public (not private[server]) so Scala emits the static forwarder Java tests need.
  def friendlyError(error: Throwable): String = {
    var cause = error
    while ((cause.isInstanceOf[java.util.concurrent.CompletionException] || cause.isInstanceOf[java.util.concurrent.ExecutionException]) && cause.getCause() != null) cause = cause.getCause()
    if (cause.isInstanceOf[LinkEditors.EditorError]) return cause.getMessage()
    cause match {
      case conflict: io.linkmc.core.ConflictException => conflict.kind() match {
          case io.linkmc.core.ConflictException.Kind.WORLD_EXISTS => "이미 등록된 월드입니다: " + conflict.world() + ". 기존 작업은 /템플릿 입장 " + conflict.world() + " 으로 이어가세요. 새 작업은 다른 이름으로 생성하세요."
          case io.linkmc.core.ConflictException.Kind.WORLD_DELETED => "삭제된 월드 이름은 재사용할 수 없습니다: " + conflict.world() + ". /템플릿 생성 <새이름>을 사용하세요."
          case io.linkmc.core.ConflictException.Kind.GENERAL | io.linkmc.core.ConflictException.Kind.OWNERSHIP_LOST => null
        }
      case _ => null
    }
  }
  private def count(args: Array[String], expected: Int): Unit = { if (args.length != expected) throw new IllegalArgumentException("인자 개수가 올바르지 않습니다. /링크 또는 /템플릿으로 사용법을 확인하세요.") }
}
