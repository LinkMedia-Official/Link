package io.linkmc.server

import io.linkmc.api.LinkApi
import java.util.List
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Consumer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault

/** Backend commands enqueue the same durable routes used by the plugin API. */
private[server] final class LinkTravelCommand private[server] (name: String, network: LinkApi.Network, journal: Boolean,
                                                               dispatch: Consumer[Runnable], logError: Consumer[Throwable])
    extends Command(name, "Link 이동", if (name.equals("warp")) "/warp <이름> | /워프 <이름>" else "/spawn | /스폰", List.of(if (name.equals("warp")) "워프" else "스폰")) {
  private var completions: LinkCompletions = null
  private var groups: LinkApi.WarpGroups = null

  if (name.equals("warp")) setPermission("link.warp")

  override def execute(sender: CommandSender, label: String, args: Array[String]): Boolean = {
    if (!testPermission(sender)) return true
    val player = sender match {
      case player: Player => player
      case _ =>
        sender.sendMessage("플레이어만 사용할 수 있는 명령어입니다.")
        return true
    }
    if (args.length != (if (getName().equals("warp")) 1 else 0)) {
      sender.sendMessage(getUsage())
      return true
    }
    if (!journal) {
      sender.sendMessage("Link 이동은 journal 모드에서만 사용할 수 있습니다.")
      return true
    }
    val id = player.getUniqueId()
    if (getName().equals("warp") && groups != null) {
      groups.travel(id, UUID.randomUUID(), args(0)).whenComplete((v: Void, e: Throwable) => dispatch.accept(() => {
        if (e != null) { logError.accept(e); if (player.isOnline()) player.sendMessage("워프 요청에 실패했습니다. 이름 또는 진행 중인 이동을 확인하세요.") }
        else if (player.isOnline()) player.sendMessage("워프 이동을 요청했습니다. 채널 생성이 필요하면 최대 60초 동안 기다립니다.")
      })); return true
    }
    val destination: CompletionStage[LinkApi.Destination] = if (getName().equals("spawn"))
      CompletableFuture.completedFuture[LinkApi.Destination](null)
    else network.warp(args(0)).thenApply[LinkApi.Destination](found => found.orElse(null))
    destination.thenCompose[LinkApi.Reservation](target => {
      if (target == null && getName().equals("warp")) {
        dispatch.accept(() => { if (player.isOnline()) player.sendMessage("등록되지 않은 워프입니다.") })
        CompletableFuture.completedFuture[LinkApi.Reservation](null)
      } else network.requestTravel(id, UUID.randomUUID(), target)
    }).whenComplete((reservation: LinkApi.Reservation, error: Throwable) => dispatch.accept(() => {
      if (error != null) {
        logError.accept(error)
        if (player.isOnline()) player.sendMessage("이동 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.")
      } else if (reservation != null && player.isOnline()) {
        player.sendMessage("이동을 요청했습니다.")
      }
    }))
    true
  }

  override def tabComplete(sender: CommandSender, alias: String, args: Array[String]): List[String] = {
    if (!getName().equals("warp") || !journal || !sender.isInstanceOf[Player] || !testPermissionSilent(sender) || args.length != 1 || completions == null) return List.of()
    completions.warps(args(0))
  }
}

object LinkTravelCommand {
  def register(api: LinkApi, completions: LinkCompletions): Unit = {
    val server = org.bukkit.Bukkit.getServer()
    if (server.getPluginManager().getPermission("link.warp") == null)
      server.getPluginManager().addPermission(new Permission("link.warp", PermissionDefault.OP))
    val names = List.of("spawn", "warp").iterator()
    while (names.hasNext()) {
      val name = names.next()
      val command = new LinkTravelCommand(name, api.network(), NativeJournalImpl.enabled(),
        task => net.minecraft.server.MinecraftServer.getServer().execute(task),
        error => net.minecraft.server.MinecraftServer.LOGGER.error("Link travel request failed", error))
      command.completions = completions; command.groups = api.warpGroups()
      server.getCommandMap().register("link", command)
    }
  }
}
