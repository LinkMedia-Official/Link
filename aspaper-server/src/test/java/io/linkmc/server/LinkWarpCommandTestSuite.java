package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LinkWarpCommandTestSuite {
    LinkApi.Network network;Player player;LinkCompletions completions;
    @BeforeEach void setup(){
        network=mock(LinkApi.Network.class);player=mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        World world=mock(World.class);when(world.getName()).thenReturn("mall");when(player.getLocation()).thenReturn(new Location(world,1.25,65,3.75,90,15));
        completions=new LinkCompletions(List::of,"lobby");completions.replace(new LinkCompletions.Catalog(Map.of(),List.of(),List.of("shop","spawn")));
    }
    LinkWarpCommand command(String name){return new LinkWarpCommand(name,network,"node-a",50,true,completions,Runnable::run,error->{});}
    @Test void capturesCompletePositionAndAcknowledgesOnlyAfterCommit(){
        var pending=new CompletableFuture<Void>();when(network.setWarp(anyString(),any(),anyInt())).thenReturn(pending);
        command("setwarp").execute(player,"워프설정",new String[]{"shop"});
        verify(network).setWarp("shop",new LinkApi.Destination("node-a","mall",1.25,65,3.75,90,15),50);
        verify(player,never()).sendMessage(anyString());pending.complete(null);verify(player).sendMessage(contains("DB에 저장"));
    }
    @Test void deleteReportsMissingAndDeleted(){
        when(network.deleteWarp("shop")).thenReturn(CompletableFuture.completedFuture(true));when(network.deleteWarp("missing")).thenReturn(CompletableFuture.completedFuture(false));
        command("delwarp").execute(player,"워프삭제",new String[]{"shop"});verify(player).sendMessage(contains("DB에서 삭제"));
        command("delwarp").execute(player,"워프삭제",new String[]{"missing"});verify(player).sendMessage("등록되지 않은 워프입니다: missing");
    }
    @Test void listingReadsSqlAndPaginates(){
        var names=java.util.stream.IntStream.range(0,12).mapToObj(i->"warp"+i).toList();when(network.warps()).thenReturn(CompletableFuture.completedFuture(names));
        command("warps").execute(player,"워프목록",new String[]{"2"});verify(network).warps();
        verify(player).sendMessage("/워프 warp10");verify(player).sendMessage("/워프 warp11");verify(player,never()).sendMessage("/워프 warp0");
    }
    @Test void failedSqlDoesNotSendSuccessOrSecret(){
        when(network.setWarp(anyString(),any(),anyInt())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("secret credentials")));
        command("setwarp").execute(player,"setwarp",new String[]{"shop"});verify(player).sendMessage("워프 작업에 실패했습니다. 서버 로그를 확인하세요.");
        verify(player,never()).sendMessage(contains("secret"));verify(player,never()).sendMessage(contains("DB에 저장"));
    }
    @Test void editingConsoleAndInvalidArgumentsDoNotWrite(){
        when(player.getPersistentDataContainer().has(LinkEditors.KEY,PersistentDataType.STRING)).thenReturn(true);
        command("setwarp").execute(player,"워프설정",new String[]{"shop"});
        CommandSender console=mock(CommandSender.class);when(console.hasPermission(anyString())).thenReturn(true);
        command("setwarp").execute(console,"setwarp",new String[]{"shop"});
        command("delwarp").execute(player,"워프삭제",new String[]{"../invalid"});
        command("warps").execute(player,"워프목록",new String[]{"0"});verifyNoInteractions(network);
    }
    @Test void permissionsModesAliasesAndCompletions(){
        assertEquals(List.of("shop","spawn"),command("delwarp").tabComplete(player,"워프삭제",new String[]{"s"}));
        assertTrue(command("setwarp").getAliases().contains("워프설정"));
        when(player.hasPermission(anyString())).thenReturn(false);assertTrue(command("delwarp").tabComplete(player,"delwarp",new String[]{""}).isEmpty());
        try(var bukkit=mockStatic(Bukkit.class)){
            bukkit.when(Bukkit::permissionMessage).thenReturn(net.kyori.adventure.text.Component.text("Denied"));command("setwarp").execute(player,"setwarp",new String[]{"shop"});
        }
        when(player.hasPermission(anyString())).thenReturn(true);
        new LinkWarpCommand("setwarp",network,"a",50,false,completions,Runnable::run,error->{}).execute(player,"setwarp",new String[]{"shop"});
        verifyNoInteractions(network);
    }
}
