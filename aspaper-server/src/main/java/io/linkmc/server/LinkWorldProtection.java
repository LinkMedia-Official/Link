package io.linkmc.server;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.persistence.PersistentDataType;

/** Per-world template rules. Missing settings preserve vanilla behavior. */
final class LinkWorldProtection implements Listener {
    static final NamespacedKey KEY=new NamespacedKey("link","template-protection");
    enum Rule { BREAK, PLACE, INTERACT, CONTAINERS, EXPLOSIONS, ENTITY_CHANGES, FIRE }
    record Policy(boolean breakBlocks,boolean placeBlocks,boolean interact,boolean containers,boolean explosions,boolean entityChanges,boolean fire){
        static Policy defaults(){return new Policy(true,true,true,true,true,true,true);}
        boolean allowed(Rule rule){return switch(rule){case BREAK->breakBlocks;case PLACE->placeBlocks;case INTERACT->interact;case CONTAINERS->containers;case EXPLOSIONS->explosions;case ENTITY_CHANGES->entityChanges;case FIRE->fire;};}
        Policy toggle(Rule rule){return new Policy(rule==Rule.BREAK?!breakBlocks:breakBlocks,rule==Rule.PLACE?!placeBlocks:placeBlocks,rule==Rule.INTERACT?!interact:interact,rule==Rule.CONTAINERS?!containers:containers,rule==Rule.EXPLOSIONS?!explosions:explosions,rule==Rule.ENTITY_CHANGES?!entityChanges:entityChanges,rule==Rule.FIRE?!fire:fire);}
    }
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    static Policy policy(World world){
        String value=world.getPersistentDataContainer().get(KEY,PersistentDataType.STRING);if(value==null)return Policy.defaults();
        try{Policy p=JSON.fromJson(value,Policy.class);return p!=null?p:new Policy(false,false,false,false,false,false,false);}catch(RuntimeException e){return new Policy(false,false,false,false,false,false,false);}
    }
    static void set(World world,Policy policy){world.getPersistentDataContainer().set(KEY,PersistentDataType.STRING,JSON.toJson(policy));}
    static boolean allowed(World world,Player player,Rule rule){
        if(player!=null){
            if(player.hasPermission("link.protection.bypass"))return true;
            if(player.hasPermission("link.admin")){try{var visit=LinkEditors.visit(player);if(visit!=null&&visit.workspace().equals(world.getName())&&player.getWorld().equals(world))return true;}catch(LinkEditors.EditorError ignored){}}
        }
        return policy(world).allowed(rule);
    }
    static void register(){
        var manager=Bukkit.getPluginManager();if(manager.getPermission("link.protection.bypass")==null)manager.addPermission(new org.bukkit.permissions.Permission("link.protection.bypass",org.bukkit.permissions.PermissionDefault.FALSE));
        manager.registerEvents(new LinkWorldProtection(),new com.infernalsuite.asp.InternalPlugin());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(!allowed(e.getBlock().getWorld(),e.getPlayer(),Rule.BREAK))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(!allowed(e.getBlock().getWorld(),e.getPlayer(),Rule.PLACE))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fill(PlayerBucketFillEvent e){if(!allowed(e.getBlock().getWorld(),e.getPlayer(),Rule.BREAK))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void empty(PlayerBucketEmptyEvent e){if(!allowed(e.getBlock().getWorld(),e.getPlayer(),Rule.PLACE))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){
        if(e.getClickedBlock()!=null&&!allowed(e.getClickedBlock().getWorld(),e.getPlayer(),Rule.INTERACT)){
            e.setUseInteractedBlock(Event.Result.DENY);if(e.getAction()==Action.RIGHT_CLICK_BLOCK)e.setUseItemInHand(Event.Result.DENY);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){
        if(!(e.getPlayer() instanceof Player p)||e.getInventory().getLocation()==null)return;
        if(!allowed(e.getInventory().getLocation().getWorld(),p,Rule.CONTAINERS))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){if(!allowed(e.getLocation().getWorld(),null,Rule.EXPLOSIONS))e.blockList().clear();}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){if(!allowed(e.getBlock().getWorld(),null,Rule.EXPLOSIONS))e.blockList().clear();}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityChange(EntityChangeBlockEvent e){if(!allowed(e.getBlock().getWorld(),e.getEntity() instanceof Player p?p:null,Rule.ENTITY_CHANGES))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(!allowed(e.getBlock().getWorld(),null,Rule.FIRE))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void ignite(BlockIgniteEvent e){if(!allowed(e.getBlock().getWorld(),e.getPlayer(),Rule.FIRE))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spread(BlockSpreadEvent e){if((e.getSource().getType()==Material.FIRE||e.getSource().getType()==Material.SOUL_FIRE)&&!allowed(e.getBlock().getWorld(),null,Rule.FIRE))e.setCancelled(true);}
}
