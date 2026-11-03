package io.linkmc.server;

import io.linkmc.api.LinkApi;
import io.linkmc.core.TeleportStore;
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

class LinkTeleportCommandTestSuite {
    LinkApi.Teleports service;Player player;UUID id;
    @BeforeEach void setup(){
        service=mock(LinkApi.Teleports.class);player=mock(Player.class);id=UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);when(player.getName()).thenReturn("Alice");when(player.hasPermission("link.tpa")).thenReturn(true);
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(service.request(any(),anyString(),anyBoolean())).thenReturn(new CompletableFuture<>());
        when(service.respond(any(),nullable(String.class),anyBoolean())).thenReturn(new CompletableFuture<>());
        when(service.cancel(any())).thenReturn(new CompletableFuture<>());
    }
    LinkTeleportCommand command(String name){return new LinkTeleportCommand(name,service,true,Runnable::run,error->{},(id,action,prefix)->List.of("Alice","Bob"));}
    @Test void koreanRequestAndHereUseCorrectDirection(){
        command("tpa").execute(player,"티피요청",new String[]{"Bob"});verify(service).request(id,"Bob",false);
        command("tpahere").execute(player,"티피여기",new String[]{"Bob"});verify(service).request(id,"Bob",true);
        assertTrue(command("tpa").getAliases().contains("티피요청"));assertTrue(command("tpahere").getAliases().contains("티피여기"));
    }
    @Test void acceptanceDenialAndCancellationIdentifyActor(){
        command("tpaccept").execute(player,"티피수락",new String[0]);verify(service).respond(id,null,true);
        command("tpdeny").execute(player,"티피거절",new String[]{"Bob"});verify(service).respond(id,"Bob",false);
        command("tpacancel").execute(player,"티피취소",new String[0]);verify(service).cancel(id);
    }
    @Test void confirmationWaitsForSqlAndErrorsDoNotLeak(){
        var pending=new CompletableFuture<LinkApi.TeleportRequest>();when(service.request(id,"Bob",false)).thenReturn(pending);
        command("tpa").execute(player,"티피요청",new String[]{"Bob"});verify(player,never()).sendMessage(anyString());
        pending.completeExceptionally(new IllegalStateException("secret database"));verify(player).sendMessage("티피 요청 처리에 실패했습니다. 잠시 후 다시 시도하세요.");verify(player,never()).sendMessage(contains("secret"));
        when(service.respond(id,null,true)).thenReturn(CompletableFuture.failedFuture(new TeleportStore.Rejected("요청이 없습니다")));
        command("tpaccept").execute(player,"티피수락",new String[0]);verify(player).sendMessage("요청이 없습니다");
    }
    @Test void invalidArgumentsConsoleAndEditorDoNotTouchSql(){
        command("tpa").execute(player,"tpa",new String[0]);command("tpaccept").execute(player,"tpaccept",new String[]{"Bob","extra"});command("tpacancel").execute(player,"tpacancel",new String[]{"extra"});
        CommandSender console=mock(CommandSender.class);when(console.hasPermission("link.tpa")).thenReturn(true);command("tpa").execute(console,"tpa",new String[]{"Bob"});
        when(player.getPersistentDataContainer().has(LinkEditors.KEY,PersistentDataType.STRING)).thenReturn(true);command("tpa").execute(player,"tpa",new String[]{"Bob"});verifyNoInteractions(service);
    }
    @Test void permissionDenialAndToolsModeDoNotTouchSql(){
        when(player.hasPermission("link.tpa")).thenReturn(false);
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::permissionMessage).thenReturn(net.kyori.adventure.text.Component.text("Denied"));command("tpa").execute(player,"tpa",new String[]{"Bob"});}
        assertTrue(command("tpa").tabComplete(player,"tpa",new String[]{""}).isEmpty());
        when(player.hasPermission("link.tpa")).thenReturn(true);new LinkTeleportCommand("tpa",service,false,Runnable::run,error->{},(id,a,p)->List.of()).execute(player,"tpa",new String[]{"Bob"});verifyNoInteractions(service);
    }
    @Test void tabSuggestionsExcludeSelfAndCancellationArguments(){
        assertEquals(List.of("Bob"),command("tpa").tabComplete(player,"티피요청",new String[]{""}));assertTrue(command("tpacancel").tabComplete(player,"티피취소",new String[]{""}).isEmpty());
        var suggestions=mock(LinkTeleportCommand.Suggestions.class);when(suggestions.get(id,"tpaccept","B")).thenReturn(List.of("Bob"));
        var command=new LinkTeleportCommand("tpaccept",service,true,Runnable::run,error->{},suggestions);
        assertEquals(List.of("Bob"),command.tabComplete(player,"티피수락",new String[]{"B"}));verify(suggestions).get(id,"tpaccept","B");
    }
}
