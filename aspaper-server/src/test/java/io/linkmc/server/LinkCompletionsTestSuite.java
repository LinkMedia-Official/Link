package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LinkCompletionsTestSuite {
    private LinkCompletions catalog(){
        var result=new LinkCompletions(()->List.of("link-spawn","editor"),"lobby");
        result.replace(new LinkCompletions.Catalog(Map.of("lobby",List.of(1,3),"arena:duel",List.of(2)),List.of("saved","editor"),List.of("shop","SpawnHub")));
        return result;
    }
    @Test void subcommandsFilterByPrefix(){
        assertEquals(List.of("save"),catalog().admin(new String[]{"sa"}));
        assertEquals(20,catalog().admin(new String[]{""}).size());
    }
    @Test void worldSuggestionsDistinguishLoadedAndStored(){
        assertEquals(List.of("editor","link-spawn"),catalog().admin(new String[]{"save",""}));
        assertEquals(List.of("saved"),catalog().admin(new String[]{"load",""}));
        assertEquals(List.of("saved"),catalog().admin(new String[]{"delete",""}));
    }
    @Test void templateVersionsAndStorageModesFollowArgumentPosition(){
        assertEquals(List.of("arena:duel","lobby"),catalog().admin(new String[]{"create","new",""}));
        assertEquals(List.of("1","3"),catalog().admin(new String[]{"create","new","lobby",""}));
        assertEquals(List.of("2"),catalog().admin(new String[]{"create","new","arena:duel",""}));
        assertEquals(List.of("persistent"),catalog().admin(new String[]{"create","new","lobby","1","p"}));
    }
    @Test void publishingSuggestsUnusedNextVersion(){
        assertEquals(List.of("4"),catalog().admin(new String[]{"publish","editor","lobby",""}));
        assertEquals(List.of("1"),catalog().admin(new String[]{"publish","editor","new-template",""}));
    }
    @Test void newNamesAndExtraArgumentsDoNotSuggestPlayers(){
        assertTrue(catalog().admin(new String[]{"editor",""}).isEmpty());
        assertTrue(catalog().admin(new String[]{"create",""}).isEmpty());
        assertTrue(catalog().admin(new String[]{"worlds",""}).isEmpty());
        assertTrue(catalog().admin(new String[]{"save","editor",""}).isEmpty());
    }
    @Test void warpNamesAreFilteredAndSnapshotIsDefensive(){
        var data=catalog();assertEquals(List.of("SpawnHub"),data.warps("sp"));
        var names=new ArrayList<>(List.of("shop"));data.replace(new LinkCompletions.Catalog(Map.of(),List.of(),names));
        names.clear();assertEquals(List.of("shop"),data.warps(""));
    }
    @Test void commandPermissionIsCheckedWithoutCallingApi(){
        var api=mock(LinkApi.class);var sender=mock(CommandSender.class);
        var command=new LinkCommand(api,catalog());
        assertTrue(command.tabComplete(sender,"link",new String[]{""}).isEmpty());
        when(sender.hasPermission("link.admin")).thenReturn(true);
        assertEquals(List.of("save"),command.tabComplete(sender,"link:link",new String[]{"sa"}));
        verifyNoInteractions(api);
    }
    @Test void koreanAndNestedTemplateSuggestions(){
        assertEquals(List.of("저장"),catalog().admin(new String[]{"저"}));
        assertEquals(List.of("editor","link-spawn"),catalog().admin(new String[]{"저장",""}));
        assertEquals(List.of("영구"),catalog().admin(new String[]{"생성","world","lobby","1","영"}));
        assertEquals(List.of("1","3"),catalog().template(new String[]{"편집","lobby",""}));
        assertEquals(List.of("4"),catalog().admin(new String[]{"템플릿","저장후나가기","lobby",""}));
        assertEquals(List.of("editor","link-spawn","saved"),catalog().template(new String[]{"입장",""}));
        assertTrue(catalog().template(new String[]{"나가기",""}).isEmpty());
    }
}
