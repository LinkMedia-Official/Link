package io.linkmc.server

import io.linkmc.api.LinkApi
import java.util.{List, Locale, Set}
import java.util.concurrent.{CompletionException, CompletionStage}
import java.util.function.Consumer
import org.bukkit.command.{Command, CommandSender}
import org.bukkit.entity.Player
import org.bukkit.permissions.{Permission, PermissionDefault}

private[server] final class LinkUtilityCommand private[server] (name: String, api: LinkApi.Utilities, dispatch: Consumer[Runnable], errors: Consumer[Throwable])
    extends Command(name, "Link 플레이어 유틸리티", LinkUtilityCommand.usage(name), LinkUtilityCommand.aliases(name)) {
  setPermission("link." + (if (name.endsWith("speed")) "speed" else name))

  override def execute(sender: CommandSender, label: String, args: Array[String]): Boolean = {
    if (!testPermission(sender)) return true
    val p = sender match {
      case p: Player => p
      case _ => sender.sendMessage("플레이어만 사용할 수 있습니다."); return true
    }
    try {
      val name = getName(); var operation: CompletionStage[Void] = null
      if (name.endsWith("speed")) {
        if (args.length < 1 || args.length > (if (name.equals("speed")) 2 else 1)) throw new LinkUtilities.Rejected(getUsage())
        val `type` = if (name.equals("flyspeed")) LinkApi.MovementMode.FLY else if (name.equals("walkspeed")) LinkApi.MovementMode.WALK else if (args.length == 2) LinkUtilityCommand.mode(args(1)) else LinkApi.MovementMode.AUTO
        operation = api.speed(p.getUniqueId(), LinkUtilityCommand.level(args(0)), `type`)
      } else if (name.equals("fly")) {
        if (args.length > 1) throw new LinkUtilities.Rejected(getUsage())
        val enabled: java.lang.Boolean = if (args.length == 0) null else args(0).toLowerCase(Locale.ROOT) match {
          case "on" | "켜기" => java.lang.Boolean.TRUE
          case "off" | "끄기" => java.lang.Boolean.FALSE
          case _ => throw new LinkUtilities.Rejected(getUsage())
        }
        operation = api.flight(p.getUniqueId(), enabled)
      } else { if (args.length != 0) throw new LinkUtilities.Rejected(getUsage()); operation = if (name.equals("heal")) api.heal(p.getUniqueId()) else api.feed(p.getUniqueId()) }
      operation.whenComplete((v: Void, error: Throwable) => dispatch.accept(() => {
        if (error == null) p.sendMessage("적용하고 저장했습니다: " + label)
        else {
          var cause = error; while (cause.isInstanceOf[CompletionException] && cause.getCause() != null) cause = cause.getCause()
          if (cause.isInstanceOf[LinkUtilities.Rejected]) p.sendMessage(cause.getMessage()) else { errors.accept(error); p.sendMessage("유틸리티 처리에 실패했습니다. 서버 로그를 확인하세요.") }
        }
      }))
    } catch { case e: LinkUtilities.Rejected => p.sendMessage(e.getMessage()) }
    true
  }
  override def tabComplete(sender: CommandSender, alias: String, args: Array[String]): List[String] = {
    if (!testPermissionSilent(sender) || !sender.isInstanceOf[Player]) return List.of()
    var values: List[String] = List.of()
    if (args.length == 1) { if (getName().endsWith("speed")) values = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "reset", "초기화") else if (getName().equals("fly")) values = List.of("on", "off", "켜기", "끄기") }
    else if (args.length == 2 && getName().equals("speed")) values = List.of("walk", "fly", "걷기", "비행")
    if (args.length == 0) return List.of()
    val prefix = args(args.length - 1).toLowerCase(Locale.ROOT); values.stream().filter(v => v.startsWith(prefix)).toList()
  }
}

object LinkUtilityCommand {
  private[server] def aliases(name: String): List[String] = name match { case "speed" => List.of("속도"); case "flyspeed" => List.of("비행속도"); case "walkspeed" => List.of("걷기속도", "이동속도"); case "fly" => List.of("비행"); case "heal" => List.of("회복"); case _ => List.of("배고픔", "허기") }
  private[server] def usage(name: String): String = name match { case "speed" => "/속도 <0~10|초기화> [걷기|비행] | /speed <0..10|reset> [walk|fly]"; case "flyspeed" => "/비행속도 <0~10|초기화> | /flyspeed <0..10|reset>"; case "walkspeed" => "/걷기속도 <0~10|초기화> | /walkspeed <0..10|reset>"; case "fly" => "/비행 [켜기|끄기] | /fly [on|off]"; case "heal" => "/회복 | /heal"; case _ => "/배고픔 | /feed" }
  def register(api: LinkApi.Utilities): Unit = {
    val server = org.bukkit.Bukkit.getServer()
    val permissions = List.of("speed", "fly", "heal", "feed").iterator()
    while (permissions.hasNext()) { val permission = permissions.next(); if (server.getPluginManager().getPermission("link." + permission) == null) server.getPluginManager().addPermission(new Permission("link." + permission, PermissionDefault.OP)) }
    val names = List.of("speed", "flyspeed", "walkspeed", "fly", "heal", "feed").iterator()
    while (names.hasNext()) { val name = names.next(); server.getCommandMap().register("link", new LinkUtilityCommand(name, api, task => net.minecraft.server.MinecraftServer.getServer().execute(task), e => net.minecraft.server.MinecraftServer.LOGGER.error("Link utility failed", e))) }
  }
  private[server] def level(value: String): Double = {
    if (Set.of("reset", "초기화").contains(value.toLowerCase(Locale.ROOT))) return 1
    try { val n = java.lang.Double.parseDouble(value); LinkUtilities.speedValue(n, false); n }
    catch { case e: NumberFormatException => throw new LinkUtilities.Rejected("속도는 0~10 사이의 숫자 또는 초기화/reset을 입력하세요.") }
  }
  private[server] def mode(s: String): LinkApi.MovementMode = s.toLowerCase(Locale.ROOT) match {
    case "fly" | "비행" => LinkApi.MovementMode.FLY
    case "walk" | "걷기" | "이동" => LinkApi.MovementMode.WALK
    case _ => throw new LinkUtilities.Rejected("이동 종류는 걷기/walk 또는 비행/fly입니다.")
  }
}
