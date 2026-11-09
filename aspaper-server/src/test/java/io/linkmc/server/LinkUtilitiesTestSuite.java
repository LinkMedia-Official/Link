package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LinkUtilitiesTestSuite {
    Player player;UUID id;LinkUtilities utilities;
    @BeforeEach void setup(){player=mock(Player.class);id=UUID.randomUUID();when(player.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));when(player.isOnline()).thenReturn(true);when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);utilities=new LinkUtilities(Runnable::run,u->player,u->false,true);}
    @Test void speedRangeDefaultsAndFractionalValues(){
        assertEquals(0.1f,LinkUtilities.speedValue(1,true));assertEquals(0.2f,LinkUtilities.speedValue(1,false));assertEquals(0,LinkUtilities.speedValue(0,true));assertEquals(1,LinkUtilities.speedValue(10,false));assertEquals(0.05f,LinkUtilities.speedValue(0.5,true));
        for(double invalid:new double[]{-1,11,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(LinkUtilities.Rejected.class,()->LinkUtilities.speedValue(invalid,true));
    }
    @Test void automaticSpeedUsesCurrentFlightAndExplicitModeOverrides(){
        when(player.isFlying()).thenReturn(true);utilities.speed(id,3,LinkApi.MovementMode.AUTO).toCompletableFuture().join();verify(player).setFlySpeed(0.3f);
        utilities.speed(id,1,LinkApi.MovementMode.WALK).toCompletableFuture().join();verify(player).setWalkSpeed(0.2f);
        utilities.speed(id,1,LinkApi.MovementMode.FLY).toCompletableFuture().join();verify(player).setFlySpeed(0.1f);
    }
    @Test void flightEnableDisableAndGameModeGuards(){
        utilities.flight(id,true).toCompletableFuture().join();verify(player).setAllowFlight(true);verify(player).setFlying(true);
        when(player.getAllowFlight()).thenReturn(true);utilities.flight(id,null).toCompletableFuture().join();verify(player).setFlying(false);verify(player).setAllowFlight(false);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);assertThrows(CompletionException.class,()->utilities.flight(id,false).toCompletableFuture().join());
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);assertThrows(CompletionException.class,()->utilities.flight(id,true).toCompletableFuture().join());
    }
    @Test void explicitFlightPreferenceRestoresAfterLoginButLeavesOtherPlayersUntouched(){
        LinkUtilities.restoreFlight(player);verify(player,never()).setAllowFlight(anyBoolean());
        when(player.getPersistentDataContainer().get(LinkUtilities.FLIGHT,org.bukkit.persistence.PersistentDataType.BYTE)).thenReturn((byte)1);
        LinkUtilities.restoreFlight(player);verify(player).setAllowFlight(true);verify(player).setFlying(true);
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);LinkUtilities.restoreFlight(player);verify(player,times(1)).setAllowFlight(true);
    }
    @Test void unavailablePlayersDoNotMutate(){
        for(var service:List.of(new LinkUtilities(Runnable::run,u->null,u->false,true),new LinkUtilities(Runnable::run,u->player,u->true,true),new LinkUtilities(Runnable::run,u->player,u->false,false)))assertThrows(CompletionException.class,()->service.feed(id).toCompletableFuture().join());
        when(player.isDead()).thenReturn(true);assertThrows(CompletionException.class,()->utilities.feed(id).toCompletableFuture().join());verify(player,never()).setFoodLevel(anyInt());
    }
    @Test void feedSetsAllFoodFields(){utilities.feed(id).toCompletableFuture().join();verify(player).setFoodLevel(20);verify(player).setSaturation(20);verify(player).setExhaustion(0);}
    @Test void bilingualCommandsAndSuggestions(){
        var api=mock(LinkApi.Utilities.class);when(player.getUniqueId()).thenReturn(id);when(player.hasPermission(anyString())).thenReturn(true);
        when(api.speed(any(),anyDouble(),any())).thenReturn(new CompletableFuture<>());when(api.flight(any(),any())).thenReturn(new CompletableFuture<>());
        var command=new LinkUtilityCommand("speed",api,Runnable::run,e->fail(e));command.execute(player,"속도",new String[]{"3","비행"});verify(api).speed(id,3,LinkApi.MovementMode.FLY);
        command.execute(player,"speed",new String[]{"reset","walk"});verify(api).speed(id,1,LinkApi.MovementMode.WALK);
        assertTrue(command.getAliases().contains("속도"));assertTrue(command.tabComplete(player,"속도",new String[]{"초"}).contains("초기화"));assertTrue(command.tabComplete(player,"speed",new String[]{"3","f"}).contains("fly"));
        var flight=new LinkUtilityCommand("fly",api,Runnable::run,e->fail(e));flight.execute(player,"비행",new String[]{"켜기"});verify(api).flight(id,true);flight.execute(player,"fly",new String[]{"off"});verify(api).flight(id,false);
    }
    @Test void commandRejectsBadInputConsoleAndMissingPermission(){
        var api=mock(LinkApi.Utilities.class);when(player.hasPermission(anyString())).thenReturn(true);var command=new LinkUtilityCommand("speed",api,Runnable::run,e->fail(e));
        for(String value:List.of("NaN","Infinity","-1","11","abc"))command.execute(player,"speed",new String[]{value});command.execute(player,"speed",new String[]{"1","bad"});
        CommandSender console=mock(CommandSender.class);when(console.hasPermission(anyString())).thenReturn(true);command.execute(console,"speed",new String[]{"1"});
        when(player.hasPermission(anyString())).thenReturn(false);assertTrue(command.tabComplete(player,"speed",new String[]{""}).isEmpty());verifyNoInteractions(api);
    }
    @Test void successWaitsForCommitAndErrorsAreRedacted(){
        var api=mock(LinkApi.Utilities.class);when(player.hasPermission(anyString())).thenReturn(true);when(player.getUniqueId()).thenReturn(id);var future=new CompletableFuture<Void>();when(api.feed(id)).thenReturn(future);
        var command=new LinkUtilityCommand("feed",api,Runnable::run,e->{});command.execute(player,"feed",new String[0]);verify(player,never()).sendMessage(anyString());future.complete(null);verify(player).sendMessage("적용하고 저장했습니다: feed");
        when(api.feed(id)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("password=private")));command.execute(player,"feed",new String[0]);verify(player,never()).sendMessage(contains("private"));
    }
}
