package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LinkEditorsTestSuite {
    MockedStatic<Bukkit> bukkit;
    LinkApi.Worlds worlds;
    LinkEditors editors;
    Player player;
    World home,work;
    UUID id;
    AtomicReference<Location> location;
    Map<NamespacedKey,String> pdc;
    @BeforeEach void setup(){
        bukkit=mockStatic(Bukkit.class);worlds=mock(LinkApi.Worlds.class);editors=new LinkEditors(worlds,Runnable::run);
        player=mock(Player.class);home=mock(World.class);work=mock(World.class);id=UUID.randomUUID();
        UUID homeId=UUID.randomUUID();when(home.getUID()).thenReturn(homeId);when(home.getName()).thenReturn("home");
        when(work.getName()).thenReturn("draft");when(work.getSpawnLocation()).thenReturn(new Location(work,0,65,0));
        location=new AtomicReference<>(new Location(home,12,70,34,45,10));
        when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenAnswer(call->location.get().clone());when(player.getWorld()).thenAnswer(call->location.get().getWorld());
        when(player.teleport(any(Location.class))).thenAnswer(call->{location.set(call.getArgument(0));return true;});
        bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);bukkit.when(()->Bukkit.getWorld("draft")).thenReturn(work);bukkit.when(()->Bukkit.getWorld(homeId)).thenReturn(home);
        var container=mock(PersistentDataContainer.class);pdc=new HashMap<>();when(player.getPersistentDataContainer()).thenReturn(container);
        when(container.get(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(call->pdc.get(call.getArgument(0)));
        doAnswer(call->{pdc.put(call.getArgument(0),call.getArgument(2));return null;}).when(container).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());
        doAnswer(call->{pdc.remove(call.getArgument(0));return null;}).when(container).remove(any(NamespacedKey.class));
        when(worlds.loaded()).thenReturn(CompletableFuture.completedFuture(List.of(new LinkApi.WorldInfo("draft",true,0))));
        when(worlds.createEditor("draft")).thenReturn(CompletableFuture.completedFuture(new LinkApi.WorldInfo("draft",true,0)));
        when(worlds.save(anyString())).thenReturn(CompletableFuture.completedFuture(null));when(worlds.publish(anyString(),anyString(),anyInt())).thenReturn(CompletableFuture.completedFuture(null));
    }
    @AfterEach void close(){bukkit.close();}
    static void done(CompletionStage<?> stage){stage.toCompletableFuture().join();}
    @Test void settingsRejectWrongWorldOrRevokedPermissionAndSaveValidChanges(){
        done(editors.create(id,"draft"));UUID workId=UUID.randomUUID();when(work.getUID()).thenReturn(workId);
        var change=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(CompletionException.class,()->done(editors.configure(id,workId,w->change.set(true))));assertFalse(change.get());
        when(player.hasPermission("link.admin")).thenReturn(true);
        assertThrows(CompletionException.class,()->done(editors.configure(id,UUID.randomUUID(),w->change.set(true))));assertFalse(change.get());
        done(editors.configure(id,workId,w->change.set(true)));assertTrue(change.get());verify(worlds,atLeast(2)).save("draft");
    }
    @Test void createSaveExitPreservesOriginalLocation(){
        done(editors.create(id,"draft"));assertSame(work,location.get().getWorld());
        done(editors.save(id,"lobby",2,true));
        verify(worlds).publish("draft","lobby",2);assertSame(home,location.get().getWorld());
        assertEquals(12,location.get().getX());assertEquals(45,location.get().getYaw());assertTrue(pdc.isEmpty());
    }
    @Test void exitSavesDraftWithoutPublishing(){
        done(editors.enter(id,"draft"));done(editors.exit(id));
        verify(worlds).save("draft");verify(worlds,never()).publish(anyString(),anyString(),anyInt());assertTrue(pdc.isEmpty());
    }
    @Test void failedPublishKeepsVisitAndDoesNotTeleportOut(){
        done(editors.enter(id,"draft"));when(worlds.publish("draft","lobby",1)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("duplicate")));
        assertThrows(CompletionException.class,()->done(editors.save(id,"lobby",1,true)));
        assertSame(work,location.get().getWorld());assertFalse(pdc.isEmpty());
    }
    @Test void cancelledExitRetainsVisitForRetry(){
        done(editors.enter(id,"draft"));when(player.teleport(any(Location.class))).thenReturn(false);
        assertThrows(CompletionException.class,()->done(editors.exit(id)));assertFalse(pdc.isEmpty());
    }
    @Test void reconnectResumeDoesNotOverwriteReturnPosition(){
        done(editors.enter(id,"draft"));editors=new LinkEditors(worlds,Runnable::run);
        done(editors.enter(id,"draft"));done(editors.exit(id));assertSame(home,location.get().getWorld());assertEquals(34,location.get().getZ());
    }
    @Test void duplicateOperationIsRejectedWhilePublishPending(){
        done(editors.enter(id,"draft"));var pending=new CompletableFuture<Void>();when(worlds.publish("draft","lobby",2)).thenReturn(pending);
        var save=editors.save(id,"lobby",2,false);assertThrows(CompletionException.class,()->done(editors.exit(id)));
        pending.complete(null);done(save);done(editors.exit(id));
    }
    @Test void cannotPublishFromOutsideWorkspace(){
        done(editors.enter(id,"draft"));location.set(new Location(home,0,65,0));
        assertThrows(CompletionException.class,()->done(editors.save(id,"lobby",2,false)));
        verify(worlds,never()).publish(anyString(),anyString(),anyInt());
    }
    @Test void cancelledEntryDoesNotCreateVisit(){
        when(player.teleport(any(Location.class))).thenReturn(false);
        assertThrows(CompletionException.class,()->done(editors.enter(id,"draft")));assertTrue(pdc.isEmpty());
    }
    @Test void unloadedWorkspaceIsLoadedBeforeEntry(){
        when(worlds.loaded()).thenReturn(CompletableFuture.completedFuture(List.of()),CompletableFuture.completedFuture(List.of(new LinkApi.WorldInfo("draft",true,0))));
        when(worlds.load("draft")).thenReturn(CompletableFuture.completedFuture(new LinkApi.WorldInfo("draft",true,0)));
        done(editors.enter(id,"draft"));verify(worlds).load("draft");assertSame(work,location.get().getWorld());
    }
    @Test void temporaryWorkspaceIsRejected(){
        when(worlds.loaded()).thenReturn(CompletableFuture.completedFuture(List.of(new LinkApi.WorldInfo("draft",false,0))));
        assertThrows(CompletionException.class,()->done(editors.enter(id,"draft")));verify(player,never()).teleport(any(Location.class));
    }
    @Test void disconnectedPlayerAndInvalidVersionDoNotCreateWorld(){
        when(player.isOnline()).thenReturn(false);assertThrows(CompletionException.class,()->done(editors.create(id,"draft")));
        assertThrows(IllegalArgumentException.class,()->editors.edit(id,"lobby",0));verify(worlds,never()).createEditor(anyString());
    }
    @Test void failedDraftSaveDoesNotPublishOrExit(){
        done(editors.enter(id,"draft"));when(worlds.save("draft")).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SQL unavailable")));
        assertThrows(CompletionException.class,()->done(editors.save(id,"lobby",2,true)));
        verify(worlds,never()).publish(anyString(),anyString(),anyInt());assertFalse(pdc.isEmpty());assertSame(work,location.get().getWorld());
    }
    @Test void missingReturnWorldKeepsSessionAfterSaving(){
        done(editors.enter(id,"draft"));bukkit.when(()->Bukkit.getWorld(home.getUID())).thenReturn(null);
        assertThrows(CompletionException.class,()->done(editors.exit(id)));verify(worlds).save("draft");assertFalse(pdc.isEmpty());
    }
    @Test void editingClonesPublishedVersionIntoUniquePersistentWorkspace(){
        when(worlds.create(anyString(),eq("lobby"),eq(1),eq(true))).thenAnswer(call->{
            String name=call.getArgument(0);assertTrue(name.startsWith("edit-"));
            when(work.getName()).thenReturn(name);bukkit.when(()->Bukkit.getWorld(name)).thenReturn(work);
            when(worlds.loaded()).thenReturn(CompletableFuture.completedFuture(List.of(new LinkApi.WorldInfo(name,true,0))));
            return CompletableFuture.completedFuture(new LinkApi.WorldInfo(name,true,0));
        });
        done(editors.edit(id,"lobby",1));assertSame(work,location.get().getWorld());
        verify(worlds,never()).publish(anyString(),anyString(),anyInt());assertFalse(pdc.isEmpty());
    }
    @Test void invalidPersistedSessionIsReportedWithoutWorldChanges(){
        pdc.put(LinkEditors.KEY,"broken json");assertThrows(CompletionException.class,()->done(editors.exit(id)));verifyNoInteractions(worlds);
    }
}
