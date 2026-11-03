package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/** Backend commands enqueue the same durable routes used by the plugin API. */
final class LinkTravelCommand extends Command {
    private final LinkApi.Network network;
    private final boolean journal;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Throwable> logError;
    private LinkCompletions completions;

    LinkTravelCommand(String name, LinkApi.Network network, boolean journal,
                      Consumer<Runnable> dispatch, Consumer<Throwable> logError) {
        super(name, "Link 이동", name.equals("warp") ? "/warp <이름> | /워프 <이름>" : "/spawn | /스폰", List.of(name.equals("warp")?"워프":"스폰"));
        this.network = network;
        this.journal = journal;
        this.dispatch = dispatch;
        this.logError = logError;
        if (name.equals("warp")) setPermission("link.warp");
    }

    static void register(LinkApi api,LinkCompletions completions) {
        var server = org.bukkit.Bukkit.getServer();
        if (server.getPluginManager().getPermission("link.warp") == null)
            server.getPluginManager().addPermission(new Permission("link.warp", PermissionDefault.OP));
        for (String name : List.of("spawn", "warp")) {
            var command=new LinkTravelCommand(name, api.network(), NativeJournal.enabled(),
                task -> net.minecraft.server.MinecraftServer.getServer().execute(task),
                error -> net.minecraft.server.MinecraftServer.LOGGER.error("Link travel request failed", error));
            command.completions=completions;
            server.getCommandMap().register("link", command);
        }
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return true;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있는 명령어입니다.");
            return true;
        }
        if (args.length != (getName().equals("warp") ? 1 : 0)) {
            sender.sendMessage(getUsage());
            return true;
        }
        if (!journal) {
            sender.sendMessage("Link 이동은 journal 모드에서만 사용할 수 있습니다.");
            return true;
        }
        UUID id = player.getUniqueId();
        CompletionStage<LinkApi.Destination> destination = getName().equals("spawn")
            ? CompletableFuture.completedFuture(null)
            : network.warp(args[0]).thenApply(found -> found.orElse(null));
        destination.thenCompose(target -> {
            if (target == null && getName().equals("warp")) {
                dispatch.accept(() -> { if (player.isOnline()) player.sendMessage("등록되지 않은 워프입니다."); });
                return CompletableFuture.<LinkApi.Reservation>completedFuture(null);
            }
            return network.requestTravel(id, UUID.randomUUID(), target);
        }).whenComplete((reservation, error) -> dispatch.accept(() -> {
            if (error != null) {
                logError.accept(error);
                if (player.isOnline()) player.sendMessage("이동 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            } else if (reservation != null && player.isOnline()) {
                player.sendMessage("이동을 요청했습니다.");
            }
        }));
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if(!getName().equals("warp")||!journal||!(sender instanceof Player)||!testPermissionSilent(sender)||args.length!=1||completions==null)return List.of();
        return completions.warps(args[0]);
    }
}
