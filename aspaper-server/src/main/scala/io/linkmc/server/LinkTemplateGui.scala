package io.linkmc.server

import java.util.{Arrays, Locale, Set, UUID}
import org.bukkit.{Bukkit, Difficulty, GameRule, GameRules, Location, Material, NamespacedKey, World}
import org.bukkit.entity.Player
import org.bukkit.event.{EventHandler, EventPriority, Listener}
import org.bukkit.event.inventory.{ClickType, InventoryClickEvent, InventoryDragEvent}
import org.bukkit.inventory.{Inventory, InventoryHolder, ItemStack}
import org.bukkit.persistence.PersistentDataType
import net.kyori.adventure.text.Component

/** Server-owned menu. All slots are display-only; changes use the editor's serialized save path. */
private[server] final class LinkTemplateGui extends Listener {
  import LinkTemplateGui.{Menu, OWNER, act}

  @EventHandler(priority = EventPriority.HIGHEST)
  def click(event: InventoryClickEvent): Unit = {
    event.getView().getTopInventory().getHolder() match {
      case menu: Menu =>
        event.setCancelled(true)
        event.getWhoClicked() match {
          case player: Player if menu.player.equals(player.getUniqueId()) =>
            if (!(event.getRawSlot() < 0 || event.getRawSlot() >= 27 || !((event.getClick() eq ClickType.LEFT) || (event.getClick() eq ClickType.RIGHT) || (menu.page.equals("channels") && event.getRawSlot() == 10 && event.isShiftClick())))) {
              val slot = event.getRawSlot(); val right = (event.getClick() eq ClickType.RIGHT) || (event.getClick() eq ClickType.SHIFT_RIGHT); val large = event.isShiftClick()
              // Inventory replacement/teleport must happen after the current inventory event.
              // Typed as Runnable: Scala 3 would otherwise SAM-convert the lambda to BukkitRunnable and pick the
              // deprecated runTask(Plugin, BukkitRunnable) overload, which always throws.
              val task: Runnable = () => act(player, menu, slot, right, large)
              Bukkit.getScheduler().runTask(OWNER, task)
            }
          case _ =>
        }
      case _ =>
    }
  }
  @EventHandler(priority = EventPriority.HIGHEST)
  def drag(event: InventoryDragEvent): Unit = { if (event.getView().getTopInventory().getHolder().isInstanceOf[Menu]) event.setCancelled(true) }
}

object LinkTemplateGui {
  @scala.annotation.static final val CHANNELS: NamespacedKey = new NamespacedKey("link", "template-channels")
  @scala.annotation.static final val SPAWN: NamespacedKey = new NamespacedKey("link", "template-spawn")
  private val OWNER: com.infernalsuite.asp.InternalPlugin = new com.infernalsuite.asp.InternalPlugin()
  private val JSON: com.google.gson.Gson = new com.google.gson.Gson()
  private var registered: Boolean = false
  def register(): Unit = { if (!registered) { Bukkit.getPluginManager().registerEvents(new LinkTemplateGui(), OWNER); registered = true } }

  final class Menu private[server] (player0: Player, world0: World, val editors: LinkEditors) extends InventoryHolder {
    var page: String = "world"
    val player: UUID = player0.getUniqueId()
    val world: UUID = world0.getUID()
    val inventory: Inventory = Bukkit.createInventory(this, 27, Component.text("Link 템플릿 설정"))
    override def getInventory(): Inventory = inventory
  }

  // Public (not private[server]) so Scala emits the static forwarders Java callers/tests need.
  def policy(world: World): io.linkmc.api.LinkApi.ChannelPolicy = {
    val value = world.getPersistentDataContainer().get(CHANNELS, PersistentDataType.STRING)
    if (value == null) return io.linkmc.api.LinkApi.ChannelPolicy.defaults()
    try { val p = JSON.fromJson(value, classOf[io.linkmc.api.LinkApi.ChannelPolicy]); if (p == null) throw new IllegalArgumentException(); p }
    catch { case e: RuntimeException => throw new LinkEditors.EditorError("템플릿 채널 설정이 손상되었습니다.") }
  }
  def channelChange(w: World, slot: Int, right: Boolean): Unit = channelChange(w, slot, right, true)
  def channelChange(w: World, slot: Int, right: Boolean, large: Boolean): Unit = {
    val p = policy(w); var capacity = p.capacity(); var max = p.maxChannels(); var expand = p.autoExpand(); var network = p.networkWide()
    slot match {
      case 10 => capacity = Math.clamp((capacity + (if (right) -1 else 1) * (if (large) 10 else 1)).toLong, 1, 1000)
      case 11 => max = Math.clamp((max + (if (right) -1 else 1)).toLong, 1, 100)
      case 12 => expand = !expand
      case 13 => network = !network
      case _ => return
    }
    w.getPersistentDataContainer().set(CHANNELS, PersistentDataType.STRING, JSON.toJson(new io.linkmc.api.LinkApi.ChannelPolicy(capacity, max, expand, network)))
  }
  def spawn(world: World): Location = {
    val pdc = world.getPersistentDataContainer(); val value: String = if (pdc == null) null else pdc.get(SPAWN, PersistentDataType.STRING)
    if (value == null) return world.getSpawnLocation()
    try { val v = JSON.fromJson(value, classOf[Array[Double]]); if (v.length != 5 || Arrays.stream(v).anyMatch(n => !java.lang.Double.isFinite(n))) throw new IllegalArgumentException(); new Location(world, v(0), v(1), v(2), v(3).toFloat, v(4).toFloat) }
    catch { case e: RuntimeException => throw new LinkEditors.EditorError("템플릿 스폰 정보가 손상되었습니다.") }
  }
  def setSpawn(world: World, pos: Location): Unit = {
    if (!world.equals(pos.getWorld()) || !world.getWorldBorder().isInside(pos) || pos.getY() < world.getMinHeight() || pos.getY() >= world.getMaxHeight()) throw new LinkEditors.EditorError("월드 경계와 높이 안에서 스폰을 지정하세요.")
    if (!world.setSpawnLocation(pos)) throw new LinkEditors.EditorError("스폰 변경이 거부되었습니다.")
    world.getPersistentDataContainer().set(SPAWN, PersistentDataType.STRING, JSON.toJson(Array[Double](pos.getX(), pos.getY(), pos.getZ(), pos.getYaw(), pos.getPitch())))
  }
  def open(player: Player, world: World, editors: LinkEditors): Unit = {
    if (!NativeJournalImpl.enabled()) throw new LinkEditors.EditorError("템플릿 설정 GUI는 journal 모드에서 사용할 수 있습니다.")
    val menu = new Menu(player, world, editors); render(menu, world); player.openInventory(menu.inventory)
  }
  private def item(m: Menu, slot: Int, material: Material, title: String, lore: String*): Unit = {
    val item = new ItemStack(material); val meta = item.getItemMeta(); meta.displayName(Component.text(title)); meta.lore(Arrays.stream(lore.toArray[String]).map[Component]((s: String) => Component.text(s)).toList()); item.setItemMeta(meta); m.inventory.setItem(slot, item)
  }
  private def on(w: World, rule: GameRule[java.lang.Boolean]): String = if (java.lang.Boolean.TRUE.equals(w.getGameRuleValue(rule))) "켜짐" else "꺼짐"
  private def toggle(w: World, rule: GameRule[java.lang.Boolean]): Unit = { if (!w.setGameRule(rule, java.lang.Boolean.valueOf(!java.lang.Boolean.TRUE.equals(w.getGameRuleValue(rule))))) throw new LinkEditors.EditorError("게임 규칙 변경이 거부되었습니다.") }
  private[server] def render(m: Menu, w: World): Unit = {
    m.inventory.clear()
    if (m.page.equals("channels")) {
      val p = policy(w)
      item(m, 4, Material.BOOK, "템플릿 채널 설정", "발행 후 /워프그룹 <이름> <템플릿> <버전>", "기존 그룹은 변경하지 않습니다.")
      item(m, 10, Material.PLAYER_HEAD, "채널당 정원: " + p.capacity(), "좌클릭 +1 / 우클릭 -1 (Shift: 10씩)")
      item(m, 11, Material.CHEST, "그룹 최대 채널: " + p.maxChannels(), "네트워크 전체 한도", "좌클릭 +1 / 우클릭 -1")
      item(m, 12, Material.EMERALD, "자동 증설: " + (if (p.autoExpand()) "켜짐" else "꺼짐"), "끄면 최초 채널 하나만 생성합니다.")
      item(m, 13, Material.ENDER_PEARL, "생성 범위: " + (if (p.networkWide()) "네트워크 전체" else "그룹 연결 서버"), "전체: DB를 공유하는 활성 Link 버킷", "한 번 생성한 채널은 영구 저장됩니다.")
      item(m, 22, Material.ARROW, "월드 설정으로"); return
    }
    if (m.page.equals("protection")) {
      val policy = LinkWorldProtection.policy(w); val names = Array[String]("블록 파괴", "블록 설치", "블록 상호작용", "상자 등 보관함 사용", "폭발의 블록 파괴", "엔티티의 블록 변경", "불 생성·번짐·연소")
      item(m, 4, Material.BOOK, "템플릿 보호 설정", "허용 / 차단을 클릭으로 변경합니다.", "편집 중인 관리자는 건축할 수 있습니다.")
      var i = 0
      while (i < names.length) { item(m, 9 + i, Material.SHIELD, names(i) + ": " + (if (policy.allowed(LinkWorldProtection.Rule.values(i))) "허용" else "차단")); i += 1 }
      item(m, 22, Material.ARROW, "월드 설정으로"); return
    }
    val s = spawn(w); val border = w.getWorldBorder()
    item(m, 0, Material.COMPASS, "스폰: 현재 위치와 시선으로 지정", String.format(Locale.ROOT, "현재 %.2f, %.2f, %.2f / %.1f, %.1f", s.getX(), s.getY(), s.getZ(), s.getYaw(), s.getPitch()), "클릭하면 작업 월드에 저장됩니다.")
    item(m, 1, Material.ENDER_PEARL, "설정한 스폰으로 이동")
    item(m, 3, Material.CLOCK, "시간: " + w.getTime(), "클릭: 아침 → 정오 → 저녁 → 밤")
    item(m, 4, Material.SUNFLOWER, "시간 흐름: " + on(w, GameRules.ADVANCE_TIME))
    item(m, 5, Material.WATER_BUCKET, "날씨: " + (if (w.isThundering()) "천둥" else if (w.hasStorm()) "비" else "맑음"), "클릭: 맑음 → 비 → 천둥")
    item(m, 6, Material.FEATHER, "날씨 변화: " + on(w, GameRules.ADVANCE_WEATHER))
    item(m, 9, Material.IRON_SWORD, "난이도: " + w.getDifficulty(), "클릭: 평화로움 → 쉬움 → 보통 → 어려움")
    item(m, 10, Material.SHIELD, "PVP: " + on(w, GameRules.PVP))
    item(m, 11, Material.CHEST, "사망 시 인벤토리 유지: " + on(w, GameRules.KEEP_INVENTORY))
    item(m, 12, Material.ZOMBIE_HEAD, "몹 자연 생성: " + on(w, GameRules.SPAWN_MOBS))
    item(m, 14, Material.IRON_BARS, "월드 경계 크기: " + Math.round(border.getSize()), "좌클릭 +16 / 우클릭 -16", "스폰을 경계 밖으로 밀어내는 변경은 거부합니다.")
    item(m, 15, Material.TARGET, "월드 경계 중심: 현재 위치로", String.format(Locale.ROOT, "현재 %.2f, %.2f", border.getCenter().getX(), border.getCenter().getZ()))
    item(m, 18, Material.BOOK, "작업 월드: " + w.getName(), "설정은 변경할 때마다 DB 저장 경로로 반영됩니다.", "발행: /템플릿 저장 <이름> <새버전>", "이미 생성된 다른 월드는 변경하지 않습니다.")
    item(m, 20, Material.PLAYER_HEAD, "채널 설정", "정원·최대 채널·자동 증설·생성 서버 범위")
    item(m, 21, Material.SHIELD, "보호 설정", "블록 파괴·설치·상호작용 등 허용 여부")
    item(m, 22, Material.EMERALD, "작업 월드 저장", "템플릿 버전 발행은 별도입니다.")
    item(m, 25, Material.OAK_DOOR, "작업 저장 후 나가기")
    item(m, 26, Material.BARRIER, "메뉴 닫기")
  }
  private[server] def act(p: Player, menu: Menu, slot: Int, right: Boolean, large: Boolean): Unit = {
    if (!p.isOnline() || (p.getOpenInventory().getTopInventory() ne menu.inventory)) return
    try {
      val world = LinkEditors.editable(p, menu.world)
      if (!menu.page.equals("world") && slot == 22) { menu.page = "world"; render(menu, world); return }
      if (menu.page.equals("world") && (slot == 20 || slot == 21)) { menu.page = if (slot == 20) "channels" else "protection"; render(menu, world); return }
      if (slot == 26) { p.closeInventory(); return }
      if (menu.page.equals("world") && slot == 1) { p.closeInventory(); if (!p.teleport(spawn(world))) throw new LinkEditors.EditorError("스폰 이동이 취소되었습니다."); return }
      if (menu.page.equals("world") && slot == 25) { p.closeInventory(); LinkCommand.report(p, menu.editors.exit(p.getUniqueId()), "작업 월드를 저장하고 돌아왔습니다."); return }
      val page = menu.page
      if (page.equals("world") && !Set.of(0, 3, 4, 5, 6, 9, 10, 11, 12, 14, 15, 22).contains(slot)) return
      if (page.equals("channels") && (slot < 10 || slot > 13)) return
      if (page.equals("protection") && (slot < 9 || slot > 15)) return
      val position = p.getLocation()
      val result = menu.editors.configure(p.getUniqueId(), menu.world, (w: World) => { if (page.equals("channels")) channelChange(w, slot, right, large) else if (page.equals("protection")) LinkWorldProtection.set(w, LinkWorldProtection.policy(w).toggle(LinkWorldProtection.Rule.values(slot - 9))) else change(w, slot, right, position) })
      result.whenComplete((v: Void, error: Throwable) => {
        val task: Runnable = () => {
          if (error != null) LinkCommand.report(p, java.util.concurrent.CompletableFuture.failedFuture[AnyRef](error), "")
          else {
            p.sendMessage("템플릿 작업 설정을 저장했습니다. 발행본 반영은 /템플릿 저장을 사용하세요.")
            if (p.isOnline() && (p.getOpenInventory().getTopInventory() eq menu.inventory)) { try render(menu, LinkEditors.editable(p, menu.world)) catch { case e: LinkEditors.EditorError => p.closeInventory() } }
          }
        }
        Bukkit.getScheduler().runTask(OWNER, task)
        ()
      })
    } catch { case error: LinkEditors.EditorError => p.sendMessage(error.getMessage()); p.closeInventory() }
  }
  private[server] def change(w: World, slot: Int, right: Boolean, position: Location): Unit = {
    slot match {
      case 0 => setSpawn(w, position)
      case 3 => val now = w.getTime(); w.setTime(if (now < 1000) 1000L else if (now < 6000) 6000L else if (now < 12000) 12000L else if (now < 18000) 18000L else 1000L)
      case 4 => toggle(w, GameRules.ADVANCE_TIME)
      case 5 => if (w.isThundering()) { w.setThundering(false); w.setStorm(false); w.setClearWeatherDuration(12000) } else if (w.hasStorm()) { w.setThundering(true); w.setThunderDuration(12000) } else { w.setClearWeatherDuration(0); w.setStorm(true); w.setWeatherDuration(12000) }
      case 6 => toggle(w, GameRules.ADVANCE_WEATHER)
      case 9 => w.setDifficulty(Difficulty.values()((w.getDifficulty().ordinal() + 1) % Difficulty.values().length))
      case 10 => toggle(w, GameRules.PVP)
      case 11 => toggle(w, GameRules.KEEP_INVENTORY)
      case 12 => toggle(w, GameRules.SPAWN_MOBS)
      case 14 => val b = w.getWorldBorder(); val size = Math.clamp(b.getSize() + (if (right) -16 else 16), 16.0, 59999968.0); checkBorder(spawn(w), b.getCenter(), size); b.setSize(size)
      case 15 => val b = w.getWorldBorder(); checkBorder(spawn(w), position, b.getSize()); b.setCenter(position.getX(), position.getZ())
      case _ =>
    }
  }
  def checkBorder(spawn: Location, center: Location, size: Double): Unit = { if (Math.abs(spawn.getX() - center.getX()) >= size / 2 || Math.abs(spawn.getZ() - center.getZ()) >= size / 2) throw new LinkEditors.EditorError("설정한 스폰이 경계 밖으로 나가므로 변경할 수 없습니다.") }
}
