package io.linkmc.server

import io.linkmc.api.LinkApi
import io.linkmc.core.TeleportStore
import java.util.{List, UUID}
import java.util.concurrent.CompletionStage
import java.util.function.Consumer
import org.bukkit.command.{Command, CommandSender}
import org.bukkit.entity.Player
import org.bukkit.permissions.{Permission, PermissionDefault}
import org.bukkit.persistence.PersistentDataType

private[server] final class LinkTeleportCommand private[server] (name: String, service: LinkApi.Teleports, journal: Boolean, dispatch: Consumer[Runnable], errors: Consumer[Throwable], suggestions: LinkTeleportCommand.Suggestions)
    extends Command(name, "동의 후 플레이어에게 이동", name match { case "tpa" => "/티피요청 <유저>"; case "tpahere" => "/티피여기 <유저>"; case "tpaccept" => "/티피수락 [요청자]"; case "tpdeny" => "/티피거절 [요청자]"; case _ => "/티피취소" },
      List.of(name match { case "tpa" => "티피요청"; case "tpahere" => "티피여기"; case "tpaccept" => "티피수락"; case "tpdeny" => "티피거절"; case _ => "티피취소" })) {
  setPermission("link.tpa")

  override def execute(sender: CommandSender, label: String, args: Array[String]): Boolean = {
    if (!testPermission(sender)) return true
    val player = sender match {
      case player: Player => player
      case _ => sender.sendMessage("플레이어만 사용할 수 있습니다."); return true
    }
    if (!journal) { sender.sendMessage("티피 요청은 journal 모드에서 사용할 수 있습니다."); return true }
    if (player.getPersistentDataContainer().has(LinkEditors.KEY, PersistentDataType.STRING)) { sender.sendMessage("/템플릿 나가기 후 티피 요청을 사용하세요."); return true }
    val request = getName().equals("tpa") || getName().equals("tpahere")
    if ((request && args.length != 1) || (!request && args.length > 1) || (getName().equals("tpacancel") && args.length != 0)) { sender.sendMessage(getUsage()); return true }
    val operation: CompletionStage[?] = getName() match {
      case "tpa" | "tpahere" => service.request(player.getUniqueId(), args(0), getName().equals("tpahere"))
      case "tpaccept" | "tpdeny" => service.respond(player.getUniqueId(), if (args.length == 0) null else args(0), getName().equals("tpaccept"))
      case _ => service.cancel(player.getUniqueId())
    }
    // Erasure-only cast: lets the lambda see the wildcard result as AnyRef (no runtime check).
    operation.asInstanceOf[CompletionStage[AnyRef]].whenComplete((result: AnyRef, error: Throwable) => dispatch.accept(() => {
      if (error != null) {
        var cause = error; while (cause.isInstanceOf[java.util.concurrent.CompletionException] && cause.getCause() != null) cause = cause.getCause()
        if (cause.isInstanceOf[TeleportStore.Rejected]) player.sendMessage(cause.getMessage()) else { errors.accept(error); player.sendMessage("티피 요청 처리에 실패했습니다. 잠시 후 다시 시도하세요.") }
      } else if (request) player.sendMessage("티피 요청을 보냈습니다. 상대의 수락이 필요하며 60초 후 만료됩니다.")
      else if (getName().equals("tpaccept")) player.sendMessage("티피 요청을 수락했습니다. 이동을 준비합니다.")
      else if (getName().equals("tpdeny")) player.sendMessage("티피 요청을 거절했습니다.")
      else player.sendMessage(if (java.lang.Boolean.TRUE.equals(result)) "티피 요청을 취소했습니다." else "취소할 대기 요청이 없습니다.")
    })); true
  }
  override def tabComplete(sender: CommandSender, alias: String, args: Array[String]): List[String] = {
    if (!journal || !testPermissionSilent(sender)) return List.of()
    sender match {
      case player: Player if args.length == 1 && !getName().equals("tpacancel") =>
        suggestions.get(player.getUniqueId(), getName(), args(0)).stream().filter(name => !name.equalsIgnoreCase(player.getName())).toList()
      case _ => List.of()
    }
  }
}

object LinkTeleportCommand {
  trait Suggestions { def get(player: UUID, action: String, prefix: String): List[String] }

  def register(service: LinkApi.Teleports, runtime: LinkTeleportRuntime): Unit = {
    val server = org.bukkit.Bukkit.getServer()
    if (server.getPluginManager().getPermission("link.tpa") == null) server.getPluginManager().addPermission(new Permission("link.tpa", PermissionDefault.TRUE))
    val names = List.of("tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel").iterator()
    while (names.hasNext()) {
      val name = names.next()
      server.getCommandMap().register("link", new LinkTeleportCommand(name, service, NativeJournalImpl.enabled(), task => net.minecraft.server.MinecraftServer.getServer().execute(task), error => net.minecraft.server.MinecraftServer.LOGGER.error("Teleport request operation failed", error), (player, action, prefix) => runtime.suggest(player, action, prefix)))
    }
  }
}
