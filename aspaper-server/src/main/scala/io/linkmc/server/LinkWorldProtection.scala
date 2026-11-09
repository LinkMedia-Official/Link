package io.linkmc.server

import org.bukkit.{Bukkit, Material, NamespacedKey, World}
import org.bukkit.entity.Player
import org.bukkit.event.{Event, EventHandler, EventPriority, Listener}
import org.bukkit.event.block.{Action, BlockBreakEvent, BlockBurnEvent, BlockExplodeEvent, BlockIgniteEvent, BlockPlaceEvent, BlockSpreadEvent}
import org.bukkit.event.entity.{EntityChangeBlockEvent, EntityExplodeEvent}
import org.bukkit.event.player.{PlayerBucketEmptyEvent, PlayerBucketFillEvent, PlayerInteractEvent}
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.persistence.PersistentDataType

/** Per-world template rules. Missing settings preserve vanilla behavior. */
private[server] final class LinkWorldProtection extends Listener {
  import LinkWorldProtection.{Rule, allowed}

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def breakBlock(e: BlockBreakEvent): Unit = { if (!allowed(e.getBlock().getWorld(), e.getPlayer(), Rule.BREAK)) e.setCancelled(true) }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def place(e: BlockPlaceEvent): Unit = { if (!allowed(e.getBlock().getWorld(), e.getPlayer(), Rule.PLACE)) e.setCancelled(true) }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def fill(e: PlayerBucketFillEvent): Unit = { if (!allowed(e.getBlock().getWorld(), e.getPlayer(), Rule.BREAK)) e.setCancelled(true) }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def empty(e: PlayerBucketEmptyEvent): Unit = { if (!allowed(e.getBlock().getWorld(), e.getPlayer(), Rule.PLACE)) e.setCancelled(true) }
  @EventHandler(priority = EventPriority.HIGHEST) def interact(e: PlayerInteractEvent): Unit = {
    if (e.getClickedBlock() != null && !allowed(e.getClickedBlock().getWorld(), e.getPlayer(), Rule.INTERACT)) {
      e.setUseInteractedBlock(Event.Result.DENY); if (e.getAction() eq Action.RIGHT_CLICK_BLOCK) e.setUseItemInHand(Event.Result.DENY)
    }
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def open(e: InventoryOpenEvent): Unit = {
    e.getPlayer() match {
      case p: Player if e.getInventory().getLocation() != null =>
        if (!allowed(e.getInventory().getLocation().getWorld(), p, Rule.CONTAINERS)) e.setCancelled(true)
      case _ =>
    }
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def explode(e: EntityExplodeEvent): Unit = { if (!allowed(e.getLocation().getWorld(), null, Rule.EXPLOSIONS)) e.blockList().clear() }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def explode(e: BlockExplodeEvent): Unit = { if (!allowed(e.getBlock().getWorld(), null, Rule.EXPLOSIONS)) e.blockList().clear() }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def entityChange(e: EntityChangeBlockEvent): Unit = { if (!allowed(e.getBlock().getWorld(), e.getEntity() match { case p: Player => p; case _ => null }, Rule.ENTITY_CHANGES)) e.setCancelled(true) }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def burn(e: BlockBurnEvent): Unit = { if (!allowed(e.getBlock().getWorld(), null, Rule.FIRE)) e.setCancelled(true) }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def ignite(e: BlockIgniteEvent): Unit = { if (!allowed(e.getBlock().getWorld(), e.getPlayer(), Rule.FIRE)) e.setCancelled(true) }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) def spread(e: BlockSpreadEvent): Unit = { if (((e.getSource().getType() eq Material.FIRE) || (e.getSource().getType() eq Material.SOUL_FIRE)) && !allowed(e.getBlock().getWorld(), null, Rule.FIRE)) e.setCancelled(true) }
}

object LinkWorldProtection {
  @scala.annotation.static final val KEY: NamespacedKey = new NamespacedKey("link", "template-protection")

  enum Rule extends java.lang.Enum[Rule] { case BREAK, PLACE, INTERACT, CONTAINERS, EXPLOSIONS, ENTITY_CHANGES, FIRE }

  final case class Policy(breakBlocks: Boolean, placeBlocks: Boolean, interact: Boolean, containers: Boolean, explosions: Boolean, entityChanges: Boolean, fire: Boolean) {
    def allowed(rule: Rule): Boolean = rule match {
      case Rule.BREAK => breakBlocks; case Rule.PLACE => placeBlocks; case Rule.INTERACT => interact; case Rule.CONTAINERS => containers
      case Rule.EXPLOSIONS => explosions; case Rule.ENTITY_CHANGES => entityChanges; case Rule.FIRE => fire
    }
    def toggle(rule: Rule): Policy = new Policy(if (rule eq Rule.BREAK) !breakBlocks else breakBlocks, if (rule eq Rule.PLACE) !placeBlocks else placeBlocks, if (rule eq Rule.INTERACT) !interact else interact, if (rule eq Rule.CONTAINERS) !containers else containers, if (rule eq Rule.EXPLOSIONS) !explosions else explosions, if (rule eq Rule.ENTITY_CHANGES) !entityChanges else entityChanges, if (rule eq Rule.FIRE) !fire else fire)
  }
  object Policy {
    def defaults(): Policy = new Policy(true, true, true, true, true, true, true)
  }

  private val JSON: com.google.gson.Gson = new com.google.gson.Gson()

  // Public (not private[server]) so Scala emits the static forwarders Java callers/tests need.
  def policy(world: World): Policy = {
    val value = world.getPersistentDataContainer().get(KEY, PersistentDataType.STRING); if (value == null) return Policy.defaults()
    try {
      // Gson rejected an explicit null for a primitive record component (fail closed); a case class would skip it.
      val tree = com.google.gson.JsonParser.parseString(value)
      if (tree.isJsonObject) tree.getAsJsonObject.entrySet().forEach(e => if (e.getValue.isJsonNull) throw new com.google.gson.JsonParseException("null rule"))
      val p = JSON.fromJson(value, classOf[Policy]); if (p != null) p else new Policy(false, false, false, false, false, false, false)
    }
    catch { case e: RuntimeException => new Policy(false, false, false, false, false, false, false) }
  }
  def set(world: World, policy: Policy): Unit = world.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, JSON.toJson(policy))
  def allowed(world: World, player: Player, rule: Rule): Boolean = {
    if (player != null) {
      if (player.hasPermission("link.protection.bypass")) return true
      if (player.hasPermission("link.admin")) {
        try {
          val visit = LinkEditors.visit(player)
          if (visit != null && visit.workspace.equals(world.getName()) && player.getWorld().equals(world)) return true
        } catch { case ignored: LinkEditors.EditorError => }
      }
    }
    policy(world).allowed(rule)
  }
  def register(): Unit = {
    val manager = Bukkit.getPluginManager(); if (manager.getPermission("link.protection.bypass") == null) manager.addPermission(new org.bukkit.permissions.Permission("link.protection.bypass", org.bukkit.permissions.PermissionDefault.FALSE))
    manager.registerEvents(new LinkWorldProtection(), new com.infernalsuite.asp.InternalPlugin())
  }
}
