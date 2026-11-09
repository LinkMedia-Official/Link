package io.linkmc.server

import io.linkmc.api.LinkApi
import java.util.{Set, UUID}
import java.util.concurrent.{CompletableFuture, CompletionStage, ConcurrentHashMap}
import java.util.function.Supplier
import org.bukkit.{Bukkit, Location, NamespacedKey, World}
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

/** Private, persistent editing worlds; never registered as public spawn channels. */
final class LinkEditors private[server] (worlds: LinkApi.Worlds, dispatch: java.util.function.Consumer[Runnable]) extends LinkApi.Editors {
  import LinkEditors.{EditorError, JSON, KEY, Visit, available, editable, player, positive, required, visit}

  private val busy: Set[UUID] = ConcurrentHashMap.newKeySet()
  private[server] def this(worlds: LinkApi.Worlds) = this(worlds, (task: Runnable) => net.minecraft.server.MinecraftServer.getServer().execute(task))

  private def exclusive[T](id: UUID, operation: Supplier[CompletionStage[T]]): CompletionStage[T] = {
    if (!busy.add(id)) CompletableFuture.failedFuture[T](new EditorError("이전 템플릿 작업이 진행 중입니다."))
    else {
      try operation.get().whenComplete((value: T, error: Throwable) => { busy.remove(id); () })
      catch { case error: Exception => busy.remove(id); CompletableFuture.failedFuture[T](error) }
    }
  }
  private def main[T](action: Supplier[T]): CompletionStage[T] = {
    val future = new CompletableFuture[T]()
    if (!NativeJournalImpl.watch(future)) future
    else {
      try {
        dispatch.accept(() => NativeJournalImpl.run(() => {
          try NativeJournalImpl.complete[T](future, action.get())
          catch {
            case error: IllegalArgumentException => future.completeExceptionally(error); ()
            case error: RuntimeException => future.completeExceptionally(error); throw error
          }
        }))
      } catch { case error: RuntimeException => future.completeExceptionally(error); () }
      future
    }
  }
  private def enterWorld(id: UUID, workspace: String): CompletionStage[Void] =
    worlds.loaded().thenCompose[Void]((loaded: java.util.List[LinkApi.WorldInfo]) => {
      if (loaded.stream().noneMatch((w: LinkApi.WorldInfo) => w.name().equals(workspace)))
        worlds.load(workspace).thenCompose[Void]((v: LinkApi.WorldInfo) => enterWorld(id, workspace))
      else if (loaded.stream().noneMatch((w: LinkApi.WorldInfo) => w.name().equals(workspace) && w.persistent()))
        CompletableFuture.failedFuture[Void](new EditorError("임시 월드는 편집 작업 월드로 사용할 수 없습니다."))
      else main[Void](() => {
        val player0 = player(id); val previous = visit(player0)
        if (previous != null && !previous.workspace.equals(workspace)) throw new EditorError("다른 작업을 편집 중입니다. 먼저 /템플릿 나가기를 실행하세요.")
        val world = Bukkit.getWorld(workspace)
        if (world == null) throw new EditorError("작업 월드가 언로드되었습니다.")
        val back = player0.getLocation()
        val next = if (previous != null) previous else Visit(workspace, back.getWorld().getUID(), back.getX(), back.getY(), back.getZ(), back.getYaw(), back.getPitch())
        if (!player0.teleport(LinkTemplateGui.spawn(world))) throw new EditorError("다른 플러그인이 편집 월드 입장을 취소했습니다.")
        player0.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, JSON.toJson(next))
        null
      })
    })
  override def create(id: UUID, workspace: String): CompletionStage[LinkApi.WorldInfo] = {
    LinkApi.requireName(workspace)
    exclusive[LinkApi.WorldInfo](id, () => main[AnyRef](() => { available(id); null })
      .thenCompose[LinkApi.WorldInfo]((v: AnyRef) => worlds.createEditor(workspace)).thenCompose[LinkApi.WorldInfo]((info: LinkApi.WorldInfo) => worlds.save(workspace)
        .thenCompose[Void]((v: Void) => enterWorld(id, workspace)).thenApply[LinkApi.WorldInfo]((v: Void) => info)))
  }
  override def edit(id: UUID, template: String, version: Int): CompletionStage[LinkApi.WorldInfo] = {
    LinkApi.requireName(template); positive(version)
    val workspace = "edit-" + UUID.randomUUID()
    exclusive[LinkApi.WorldInfo](id, () => main[AnyRef](() => { available(id); null })
      .thenCompose[LinkApi.WorldInfo]((v: AnyRef) => worlds.create(workspace, template, version, true))
      .thenCompose[LinkApi.WorldInfo]((info: LinkApi.WorldInfo) => enterWorld(id, workspace).thenApply[LinkApi.WorldInfo]((v: Void) => info)))
  }
  override def enter(id: UUID, workspace: String): CompletionStage[Void] = {
    LinkApi.requireName(workspace); exclusive[Void](id, () => enterWorld(id, workspace))
  }
  override def openSettings(id: UUID): CompletionStage[Void] =
    main[Void](() => { val p = player(id); val world = editable(p, null); LinkTemplateGui.open(p, world, this); null })
  private[server] def configure(id: UUID, worldId: UUID, change: java.util.function.Consumer[World]): CompletionStage[Void] =
    exclusive[Void](id, () => main[String](() => { val world = editable(player(id), worldId); change.accept(world); world.getName() }).thenCompose[Void]((name: String) => worlds.save(name)))
  override def save(id: UUID, template: String, version: Int, exit: Boolean): CompletionStage[Void] = {
    LinkApi.requireName(template); positive(version)
    exclusive[Void](id, () => main[Visit](() => {
      val player0 = player(id); val visit0 = required(player0)
      if (!player0.getWorld().getName().equals(visit0.workspace)) throw new EditorError("편집 월드 안에서 저장하세요. /템플릿 입장 " + visit0.workspace)
      visit0
    }).thenCompose[Void]((visit0: Visit) => worlds.save(visit0.workspace)
      .thenCompose[Void]((v: Void) => worlds.publish(visit0.workspace, template, version))
      .thenCompose[Void]((v: Void) => if (exit) returnHome(id, visit0) else CompletableFuture.completedFuture[Void](null))))
  }
  override def exit(id: UUID): CompletionStage[Void] =
    exclusive[Void](id, () => main[Visit](() => required(player(id))).thenCompose[Void]((visit0: Visit) =>
      worlds.save(visit0.workspace).thenCompose[Void]((v: Void) => returnHome(id, visit0))))
  private def returnHome(id: UUID, visit0: Visit): CompletionStage[Void] =
    main[Void](() => {
      val player0 = player(id)
      if (!visit0.equals(required(player0))) throw new EditorError("편집 세션이 변경되었습니다.")
      val world = Bukkit.getWorld(visit0.returnWorld)
      if (world == null) throw new EditorError("복귀 월드가 로드되어 있지 않습니다. 월드를 불러온 뒤 다시 나가세요. 작업은 저장되었습니다.")
      if (!player0.teleport(new Location(world, visit0.x, visit0.y, visit0.z, visit0.yaw, visit0.pitch)))
        throw new EditorError("다른 플러그인이 복귀를 취소했습니다. 작업은 저장되었으며 다시 나갈 수 있습니다.")
      player0.getPersistentDataContainer().remove(KEY); null
    })
}

object LinkEditors {
  @scala.annotation.static final val KEY: NamespacedKey = new NamespacedKey("link", "template-editor")

  final class EditorError private[server] (message: String) extends IllegalArgumentException(message)

  final case class Visit(workspace: String, returnWorld: UUID, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
    // Java record equality: reference components via Objects.equals, primitive components via Double/Float.compare.
    override def equals(other: Any): Boolean = other match {
      case v: Visit => java.util.Objects.equals(workspace, v.workspace) && java.util.Objects.equals(returnWorld, v.returnWorld) &&
        java.lang.Double.compare(x, v.x) == 0 && java.lang.Double.compare(y, v.y) == 0 && java.lang.Double.compare(z, v.z) == 0 &&
        java.lang.Float.compare(yaw, v.yaw) == 0 && java.lang.Float.compare(pitch, v.pitch) == 0
      case _ => false
    }
    override def hashCode(): Int = java.util.Objects.hash(workspace, returnWorld, java.lang.Double.valueOf(x), java.lang.Double.valueOf(y), java.lang.Double.valueOf(z), java.lang.Float.valueOf(yaw), java.lang.Float.valueOf(pitch))
  }

  private val JSON = new com.google.gson.Gson()

  private[server] def player(id: UUID): Player = {
    val player = Bukkit.getPlayer(id)
    if (player == null || !player.isOnline()) throw new EditorError("플레이어가 접속 중이 아닙니다.")
    if (NativeJournalImpl.moving(id)) throw new EditorError("서버 이동 중에는 편집할 수 없습니다.")
    player
  }
  private[server] def visit(player: Player): Visit = {
    val value = player.getPersistentDataContainer().get(KEY, PersistentDataType.STRING)
    if (value == null) null
    else {
      try {
        // Gson rejected an explicit null for a primitive record component; a case class would silently read 0.
        val tree = com.google.gson.JsonParser.parseString(value)
        if (tree.isJsonObject) {
          val fields = Array("x", "y", "z", "yaw", "pitch"); var i = 0
          while (i < fields.length) { val element = tree.getAsJsonObject.get(fields(i)); if (element != null && element.isJsonNull) throw new IllegalArgumentException(); i += 1 }
        }
        val visit = JSON.fromJson(value, classOf[Visit])
        if (visit == null || visit.returnWorld == null) throw new IllegalArgumentException()
        LinkApi.requireName(visit.workspace)
        if (!java.lang.Double.isFinite(visit.x) || !java.lang.Double.isFinite(visit.y) || !java.lang.Double.isFinite(visit.z) || !java.lang.Float.isFinite(visit.yaw) || !java.lang.Float.isFinite(visit.pitch)) throw new IllegalArgumentException()
        visit
      } catch { case error: RuntimeException => throw new EditorError("편집 복귀 정보가 손상되었습니다. 관리자에게 문의하세요.") }
    }
  }
  private def available(id: UUID): Unit = {
    if (visit(player(id)) != null) throw new EditorError("이미 편집 중입니다. /템플릿 나가기 후 새 작업을 시작하세요.")
  }
  private[server] def required(player: Player): Visit = {
    val visit0 = visit(player)
    if (visit0 == null) throw new EditorError("편집 중인 템플릿이 없습니다. /템플릿 생성 또는 편집으로 시작하세요.")
    visit0
  }
  private[server] def editable(p: Player, expected: UUID): World = {
    if (!p.hasPermission("link.admin")) throw new EditorError("link.admin 권한이 필요합니다.")
    val visit0 = required(p); val world = p.getWorld()
    if (!world.getName().equals(visit0.workspace) || (expected != null && !expected.equals(world.getUID()))) throw new EditorError("설정을 연 편집 월드 안에서 사용하세요.")
    world
  }
  private[server] def positive(version: Int): Unit = { if (version < 1) throw new EditorError("버전은 1 이상의 정수여야 합니다.") }
}
