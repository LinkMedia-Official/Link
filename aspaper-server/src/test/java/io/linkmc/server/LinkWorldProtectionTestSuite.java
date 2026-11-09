package io.linkmc.server;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.Event;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LinkWorldProtectionTestSuite {
    World world;Player player;Block block;Map<NamespacedKey,String> data;LinkWorldProtection handler;
    @BeforeEach void setup(){
        world=mock(World.class);player=mock(Player.class);block=mock(Block.class);when(block.getWorld()).thenReturn(world);when(world.getName()).thenReturn("mine");when(player.getWorld()).thenReturn(world);
        data=new HashMap<>();var pdc=mock(PersistentDataContainer.class);when(world.getPersistentDataContainer()).thenReturn(pdc);when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(pdc.get(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(c->data.get(c.getArgument(0)));
        doAnswer(c->{data.put(c.getArgument(0),c.getArgument(2));return null;}).when(pdc).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());handler=new LinkWorldProtection();
    }
    @Test void defaultAllowsAndRoundTripPreservesIndependentFlags(){
        assertTrue(LinkWorldProtection.allowed(world,player,LinkWorldProtection.Rule.BREAK));
        LinkWorldProtection.set(world,LinkWorldProtection.Policy.defaults().toggle(LinkWorldProtection.Rule.BREAK));
        assertFalse(LinkWorldProtection.allowed(world,player,LinkWorldProtection.Rule.BREAK));assertTrue(LinkWorldProtection.allowed(world,player,LinkWorldProtection.Rule.PLACE));
    }
    @Test void breakPlaceAndUseAreBlockedEvenForAdminOutsideEditing(){
        LinkWorldProtection.set(world,new LinkWorldProtection.Policy(false,false,false,false,false,false,false));when(player.hasPermission("link.admin")).thenReturn(true);
        var broken=mock(BlockBreakEvent.class);when(broken.getBlock()).thenReturn(block);when(broken.getPlayer()).thenReturn(player);handler.breakBlock(broken);verify(broken).setCancelled(true);
        var placed=mock(BlockPlaceEvent.class);when(placed.getBlock()).thenReturn(block);when(placed.getPlayer()).thenReturn(player);handler.place(placed);verify(placed).setCancelled(true);
        var interaction=mock(PlayerInteractEvent.class);when(interaction.getClickedBlock()).thenReturn(block);when(interaction.getPlayer()).thenReturn(player);when(interaction.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);handler.interact(interaction);verify(interaction).setUseInteractedBlock(Event.Result.DENY);verify(interaction).setUseItemInHand(Event.Result.DENY);
    }
    @Test void editorBypassOnlyAppliesToCurrentWorkspaceAndExplicitPermissionWorks(){
        LinkWorldProtection.set(world,new LinkWorldProtection.Policy(false,false,false,false,false,false,false));when(player.hasPermission("link.admin")).thenReturn(true);
        String visit="{\"workspace\":\"mine\",\"returnWorld\":\""+UUID.randomUUID()+"\",\"x\":0,\"y\":65,\"z\":0,\"yaw\":0,\"pitch\":0}";
        when(player.getPersistentDataContainer().get(LinkEditors.KEY,PersistentDataType.STRING)).thenReturn(visit);assertTrue(LinkWorldProtection.allowed(world,player,LinkWorldProtection.Rule.BREAK));
        when(world.getName()).thenReturn("other");assertFalse(LinkWorldProtection.allowed(world,player,LinkWorldProtection.Rule.BREAK));when(player.hasPermission("link.protection.bypass")).thenReturn(true);assertTrue(LinkWorldProtection.allowed(world,player,LinkWorldProtection.Rule.BREAK));
    }
    @Test void explosionsProtectBlocksAndMalformedPoliciesFailClosed(){
        data.put(LinkWorldProtection.KEY,"bad-json");assertFalse(LinkWorldProtection.allowed(world,null,LinkWorldProtection.Rule.PLACE));
        var explosion=mock(EntityExplodeEvent.class);when(explosion.getLocation()).thenReturn(new Location(world,0,65,0));List<Block> blocks=new ArrayList<>(List.of(block));when(explosion.blockList()).thenReturn(blocks);handler.explode(explosion);assertTrue(blocks.isEmpty());
    }
    @Test void channelGuiSettingsRoundTripAndClamp(){
        for(int i=0;i<5;i++)LinkTemplateGui.channelChange(world,10,true);
        var p=LinkTemplateGui.policy(world);assertEquals(1,p.capacity());
        LinkTemplateGui.channelChange(world,11,true);LinkTemplateGui.channelChange(world,12,false);LinkTemplateGui.channelChange(world,13,false);
        p=LinkTemplateGui.policy(world);assertEquals(2,p.maxChannels());assertFalse(p.autoExpand());assertTrue(p.networkWide());
    }
    @Test void dynamicWarpNamesVersionsAndPagesRefreshFromCatalog(){
        var completion=new LinkCompletions(List::of,"lobby");var names=java.util.stream.IntStream.range(0,25).mapToObj(n->"mine-"+n).toList();completion.replace(new LinkCompletions.Catalog(Map.of("mine",List.of(1,3)),List.of(),names,names));
        assertEquals(List.of("1","2","3"),completion.warpPages(""));
        var command=new LinkWarpGroupCommand(mock(io.linkmc.api.LinkApi.class),completion,"node-a");when(player.hasPermission("link.admin")).thenReturn(true);
        assertEquals(names.size(),command.tabComplete(player,"워프그룹",new String[]{"mine-"}).size());
        assertEquals(List.of("mine"),command.tabComplete(player,"워프그룹",new String[]{"portal","m"}));assertEquals(List.of("1","3"),command.tabComplete(player,"워프그룹",new String[]{"portal","mine",""}));
    }
}
