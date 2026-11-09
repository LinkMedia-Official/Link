package io.linkmc.server;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkTemplateGuiTestSuite {
    @Test void exactSpawnRoundTripsThroughWorldPdcAndRejectsOutsideBorder(){
        World world=mock(World.class);WorldBorder border=mock(WorldBorder.class);var pdc=mock(PersistentDataContainer.class);
        when(world.getPersistentDataContainer()).thenReturn(pdc);when(world.getWorldBorder()).thenReturn(border);
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);when(border.isInside(any())).thenReturn(true);when(world.setSpawnLocation(any(Location.class))).thenReturn(true);
        var value=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call->{value.set(call.getArgument(2));return null;}).when(pdc).set(eq(LinkTemplateGui.SPAWN),eq(PersistentDataType.STRING),anyString());
        when(pdc.get(LinkTemplateGui.SPAWN,PersistentDataType.STRING)).thenAnswer(call->value.get());
        Location position=new Location(world,7.25,68.5,-2.75,123,17);LinkTemplateGui.setSpawn(world,position);
        assertEquals(position,LinkTemplateGui.spawn(world));verify(world).setSpawnLocation(position);
        when(border.isInside(any())).thenReturn(false);assertThrows(LinkEditors.EditorError.class,()->LinkTemplateGui.setSpawn(world,position));
        assertEquals(position,LinkTemplateGui.spawn(world));
    }
    @Test void fallbackAndMalformedSpawn(){
        World w=mock(World.class);var pdc=mock(PersistentDataContainer.class);when(w.getPersistentDataContainer()).thenReturn(pdc);Location initial=new Location(w,0,65,0);when(w.getSpawnLocation()).thenReturn(initial);
        assertSame(initial,LinkTemplateGui.spawn(w));when(pdc.get(LinkTemplateGui.SPAWN,PersistentDataType.STRING)).thenReturn("[0,1]");assertThrows(LinkEditors.EditorError.class,()->LinkTemplateGui.spawn(w));
    }
    @Test void borderChangesCannotExcludeSpawn(){
        Location center=new Location(null,0,65,0);
        assertDoesNotThrow(()->LinkTemplateGui.checkBorder(new Location(null,7,65,7),center,16));
        assertThrows(LinkEditors.EditorError.class,()->LinkTemplateGui.checkBorder(new Location(null,8,65,0),center,16));
    }
    @Test void menuCancelsTransfersAndDragIncludingPlayerInventory(){
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)){
            Player player=mock(Player.class);World world=mock(World.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(world.getUID()).thenReturn(UUID.randomUUID());
            Inventory inventory=mock(Inventory.class);bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(net.kyori.adventure.text.Component.class))).thenReturn(inventory);
            var menu=new LinkTemplateGui.Menu(player,world,mock(LinkEditors.class));when(inventory.getHolder()).thenReturn(menu);
            var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inventory);var handler=new LinkTemplateGui();
            for(ClickType type:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.SWAP_OFFHAND,ClickType.DROP,ClickType.DOUBLE_CLICK,ClickType.MIDDLE)){
                var click=mock(InventoryClickEvent.class);when(click.getView()).thenReturn(view);when(click.getWhoClicked()).thenReturn(player);when(click.getRawSlot()).thenReturn(0);when(click.getClick()).thenReturn(type);
                handler.click(click);verify(click).setCancelled(true);
            }
            var click=mock(InventoryClickEvent.class);when(click.getView()).thenReturn(view);when(click.getWhoClicked()).thenReturn(player);when(click.getRawSlot()).thenReturn(30);when(click.getClick()).thenReturn(ClickType.LEFT);handler.click(click);verify(click).setCancelled(true);
            var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(view);handler.drag(drag);verify(drag).setCancelled(true);verifyNoInteractions(menu.editors);
        }
    }
}
