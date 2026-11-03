package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LinkTemplateCommandTestSuite {
    LinkApi api;LinkApi.Editors editors;Player player;LinkTemplateCommand command;UUID id;
    @BeforeEach void setup(){
        api=mock(LinkApi.class);editors=mock(LinkApi.Editors.class);when(api.editors()).thenReturn(editors);
        player=mock(Player.class);id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.hasPermission("link.admin")).thenReturn(true);
        when(editors.create(any(),anyString())).thenReturn(new CompletableFuture<>());
        when(editors.edit(any(),anyString(),anyInt())).thenReturn(new CompletableFuture<>());
        when(editors.enter(any(),anyString())).thenReturn(new CompletableFuture<>());
        when(editors.save(any(),anyString(),anyInt(),anyBoolean())).thenReturn(new CompletableFuture<>());
        when(editors.exit(any())).thenReturn(new CompletableFuture<>());
        command=new LinkTemplateCommand(api,new LinkCompletions(List::of,"lobby"));
    }
    @Test void koreanCommandsCallSameEditorApi(){
        command.execute(player,"템플릿",new String[]{"생성","draft"});verify(editors).create(id,"draft");
        command.execute(player,"템플릿",new String[]{"편집","lobby","1"});verify(editors).edit(id,"lobby",1);
        command.execute(player,"템플릿",new String[]{"입장","draft"});verify(editors).enter(id,"draft");
        command.execute(player,"템플릿",new String[]{"저장","lobby","2"});verify(editors).save(id,"lobby",2,false);
        command.execute(player,"템플릿",new String[]{"저장후나가기","lobby","3"});verify(editors).save(id,"lobby",3,true);
        command.execute(player,"템플릿",new String[]{"나가기"});verify(editors).exit(id);
    }
    @Test void englishCommandsAndNestedKoreanCommandsWork(){
        command.execute(player,"template",new String[]{"edit","lobby","1"});verify(editors).edit(id,"lobby",1);
        new LinkCommand(api,new LinkCompletions(List::of,"lobby")).execute(player,"링크",new String[]{"템플릿","저장","lobby","2"});verify(editors).save(id,"lobby",2,false);
        assertTrue(command.getAliases().contains("템플릿"));
        assertTrue(new LinkTravelCommand("spawn",mock(LinkApi.Network.class),true,Runnable::run,error->{}).getAliases().contains("스폰"));
        assertTrue(new LinkTravelCommand("warp",mock(LinkApi.Network.class),true,Runnable::run,error->{}).getAliases().contains("워프"));
    }
    @Test void badArgumentsDoNotCallEditorApi(){
        for(String[] args:List.of(new String[]{"생성"},new String[]{"편집","lobby","0"},new String[]{"저장","lobby","no"},new String[]{"나가기","extra"}))
            command.execute(player,"템플릿",args);
        verifyNoInteractions(editors);
    }
    @Test void consoleAndDeniedTabCannotEnter(){
        CommandSender console=mock(CommandSender.class);when(console.hasPermission("link.admin")).thenReturn(true);
        command.execute(console,"템플릿",new String[]{"생성","draft"});verifyNoInteractions(editors);
        when(player.hasPermission("link.admin")).thenReturn(false);assertTrue(command.tabComplete(player,"템플릿",new String[]{""}).isEmpty());
    }
}
