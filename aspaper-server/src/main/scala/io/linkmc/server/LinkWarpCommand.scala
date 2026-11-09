package io.linkmc.server

import io.linkmc.api.LinkApi
import java.util.List
import java.util.concurrent.CompletionStage
import java.util.function.Consumer
import org.bukkit.command.{Command, CommandSender}
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

/** SQL-backed administration. The proxy never registers these commands. */
private[server] final class LinkWarpCommand private[server] (name: String, network: LinkApi.Network, node: String, capacity: Int, journal: Boolean, completions: LinkCompletions, dispatch: Consumer[Runnable], errors: Consumer[Throwable])
    extends Command(name, "Link 워프 관리", name match { case "setwarp" => "/워프설정 <이름>"; case "delwarp" => "/워프삭제 <이름>"; case _ => "/워프목록 [페이지]" },
      List.of(name match { case "setwarp" => "워프설정"; case "delwarp" => "워프삭제"; case _ => "워프목록" })) {
  setPermission(if (name.equals("warps")) "link.warp" else "link.admin")

  override def execute(sender: CommandSender, label: String, args: Array[String]): Boolean = {
    if (!testPermission(sender)) return true
    if (!journal) { sender.sendMessage("워프 관리는 journal 모드에서 사용할 수 있습니다."); return true }
    try {
      if (getName().equals("warps")) {
        if (args.length > 1) throw new IllegalArgumentException(getUsage())
        val page = if (args.length == 0) 1 else Integer.parseInt(args(0))
        if (page < 1) throw new IllegalArgumentException("페이지는 1 이상이어야 합니다.")
        reply[List[String]](sender, network.warps(), names => {
          val pages = Math.max(1, (names.size() + 9) / 10)
          if (page > pages) sender.sendMessage("마지막 페이지는 " + pages + "입니다.")
          else {
            sender.sendMessage("워프 목록 " + page + "/" + pages + " (총 " + names.size() + "개)")
            if (names.isEmpty()) sender.sendMessage("등록된 워프가 없습니다.")
            else { val it = names.subList((page - 1) * 10, Math.min(page * 10, names.size())).iterator(); while (it.hasNext()) sender.sendMessage("/워프 " + it.next()) }
          }
        }); return true
      }
      if (args.length != 1) throw new IllegalArgumentException(getUsage())
      LinkApi.requireName(args(0)); val name = args(0)
      if (getName().equals("delwarp")) {
        reply[java.lang.Boolean](sender, network.deleteWarp(name), deleted => sender.sendMessage(if (deleted.booleanValue()) "워프를 DB에서 삭제했습니다: " + name else "등록되지 않은 워프입니다: " + name)); return true
      }
      val player = sender match {
        case player: Player => player
        case _ => sender.sendMessage("워프설정은 플레이어만 사용할 수 있습니다."); return true
      }
      if (player.getPersistentDataContainer().has(LinkEditors.KEY, PersistentDataType.STRING)) {
        sender.sendMessage("편집 중에는 워프를 설정할 수 없습니다. /템플릿 나가기 후 실제 사용할 월드에서 설정하세요."); return true
      }
      val location = player.getLocation()
      val destination = new LinkApi.Destination(node, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch())
      reply[Void](sender, network.setWarp(name, destination, capacity), ignored => sender.sendMessage("워프 위치를 DB에 저장했습니다: " + name + " (같은 이름이 있으면 갱신됩니다.)"))
    } catch {
      case error: NumberFormatException => sender.sendMessage("페이지는 1 이상의 정수여야 합니다.")
      case error: IllegalArgumentException => sender.sendMessage(if ("Invalid Link identifier".equals(error.getMessage())) "워프 이름은 영문·숫자·밑줄·하이픈 등 Link 식별자를 사용하세요." else error.getMessage())
    }
    true
  }
  private def reply[T](sender: CommandSender, operation: CompletionStage[T], success: Consumer[T]): Unit = {
    operation.whenComplete((value: T, error: Throwable) => dispatch.accept(() => {
      if (error != null) { errors.accept(error); sender.sendMessage("워프 작업에 실패했습니다. 서버 로그를 확인하세요.") }
      else success.accept(value)
    }))
  }
  override def tabComplete(sender: CommandSender, alias: String, args: Array[String]): List[String] = {
    if (!journal || !testPermissionSilent(sender) || args.length != 1) return List.of()
    if (getName().equals("warps")) return completions.warpPages(args(0))
    if (getName().equals("setwarp")) completions.fixedWarps(args(0)) else completions.warps(args(0))
  }
}

object LinkWarpCommand {
  def register(network: LinkApi.Network, node: String, capacity: Int, completions: LinkCompletions): Unit = {
    val names = List.of("setwarp", "delwarp", "warps").iterator()
    while (names.hasNext()) {
      val name = names.next()
      org.bukkit.Bukkit.getServer().getCommandMap().register("link",
        new LinkWarpCommand(name, network, node, capacity, NativeJournalImpl.enabled(), completions,
          task => net.minecraft.server.MinecraftServer.getServer().execute(task),
          error => net.minecraft.server.MinecraftServer.LOGGER.error("Link warp administration failed", error)))
    }
  }
}
