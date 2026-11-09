package io.linkmc.server

import io.linkmc.api.LinkApi
import java.util.{List, Locale}
import java.util.concurrent.CompletionStage
import org.bukkit.command.{Command, CommandSender}
import org.bukkit.entity.Player

private[server] final class LinkTemplateCommand private[server] (api: LinkApi, completions: LinkCompletions)
    extends Command("template", "템플릿 편집", LinkTemplateCommand.HELP, List.of("템플릿")) {
  setPermission("link.admin")

  override def execute(sender: CommandSender, label: String, args: Array[String]): Boolean = {
    if (!testPermission(sender)) return true
    try {
      if (args.length == 0) { sender.sendMessage(LinkTemplateCommand.HELP); return true }
      val action = LinkTemplateCommand.action(args(0))
      if (action.equals("list")) {
        LinkTemplateCommand.count(args, 1); LinkCommand.report(sender, api.worlds().templates(), "템플릿 목록"); return true
      }
      val player = sender match {
        case player: Player => player
        case _ => sender.sendMessage("편집 명령어는 플레이어만 사용할 수 있습니다."); return true
      }
      if (action.equals("settings")) {
        LinkTemplateCommand.count(args, 1)
        api.editors().openSettings(player.getUniqueId()).whenComplete((v: Void, e: Throwable) => { if (e != null) LinkCommand.report(sender, java.util.concurrent.CompletableFuture.failedFuture[AnyRef](e), "") })
        return true
      }
      val operation: CompletionStage[?] = action match {
        case "new" => LinkTemplateCommand.count(args, 2); api.editors().create(player.getUniqueId(), args(1))
        case "edit" => LinkTemplateCommand.count(args, 3); api.editors().edit(player.getUniqueId(), args(1), LinkTemplateCommand.version(args(2)))
        case "enter" => LinkTemplateCommand.count(args, 2); api.editors().enter(player.getUniqueId(), args(1))
        case "save" | "saveexit" => LinkTemplateCommand.count(args, 3); api.editors().save(player.getUniqueId(), args(1), LinkTemplateCommand.version(args(2)), action.equals("saveexit"))
        case "exit" => LinkTemplateCommand.count(args, 1); api.editors().exit(player.getUniqueId())
        case _ => throw new IllegalArgumentException(LinkTemplateCommand.HELP)
      }
      val success = action match {
        case "new" | "edit" | "enter" => "편집 월드에 입장했습니다. /템플릿 저장 <이름> <새버전> 또는 /템플릿 나가기를 사용하세요."
        case "exit" => "작업 월드를 저장하고 원래 위치로 돌아왔습니다. 템플릿 발행은 /템플릿 저장으로 진행하세요."
        case "saveexit" => "템플릿을 발행하고 원래 위치로 돌아왔습니다."
        case _ => "템플릿을 발행했습니다. /템플릿 나가기로 돌아갈 수 있습니다."
      }
      LinkCommand.report(sender, operation, success)
    } catch { case error: IllegalArgumentException => sender.sendMessage(error.getMessage()) }
    true
  }
  override def tabComplete(sender: CommandSender, alias: String, args: Array[String]): List[String] =
    if (testPermissionSilent(sender)) completions.template(args) else List.of()
}

object LinkTemplateCommand {
  final val HELP = "/템플릿 생성 <작업월드> | 편집 <이름> <버전> | 입장 <작업월드> | 저장 <이름> <새버전> | 나가기 | 저장후나가기 <이름> <새버전> | 설정 | 목록"
  private[server] def action(value: String): String = value.toLowerCase(Locale.ROOT) match {
    case "설정" | "gui" => "settings"; case "생성" => "new"; case "편집" => "edit"; case "입장" => "enter"; case "저장" => "save"
    case "나가기" => "exit"; case "저장후나가기" => "saveexit"; case "목록" => "list"
    case _ => value.toLowerCase(Locale.ROOT)
  }
  private[server] def version(value: String): Int = {
    try { val version = Integer.parseInt(value); LinkEditors.positive(version); version }
    catch { case error: NumberFormatException => throw new IllegalArgumentException("버전은 1 이상의 정수여야 합니다.") }
  }
  private def count(args: Array[String], expected: Int): Unit = { if (args.length != expected) throw new IllegalArgumentException(HELP) }
}
