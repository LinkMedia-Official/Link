package io.linkmc.server

import io.linkmc.api.LinkApi
import java.util.{Objects, UUID}
import java.util.concurrent.{CompletableFuture, CompletionStage}
import java.util.function.{Consumer, Function, Predicate}
import org.bukkit.{Bukkit, GameMode, NamespacedKey}
import org.bukkit.entity.Player
import org.bukkit.attribute.Attribute

final class LinkUtilities private[server] (dispatch: Consumer[Runnable], players: Function[UUID, Player], moving: Predicate[UUID], journal: Boolean) extends LinkApi.Utilities {
  import LinkUtilities.{FLIGHT, Rejected, speedValue}

  private[server] def this() = this((task: Runnable) => net.minecraft.server.MinecraftServer.getServer().execute(task), (id: UUID) => Bukkit.getPlayer(id), (id: UUID) => NativeJournalImpl.moving(id), NativeJournalImpl.enabled())

  private def run(id: UUID, action: Consumer[Player]): CompletionStage[Void] = {
    val future = new CompletableFuture[Void]()
    if (!journal) CompletableFuture.failedFuture[Void](new Rejected("유틸리티는 journal 모드에서 사용할 수 있습니다."))
    else if (!NativeJournalImpl.watch(future)) future
    else {
      try {
        dispatch.accept(() => NativeJournalImpl.run(() => {
          try {
            val p = players.apply(id)
            if (p == null || !p.isOnline() || p.isDead()) throw new Rejected("살아 있는 접속 플레이어만 사용할 수 있습니다.")
            if (moving.test(id)) throw new Rejected("서버 이동 중에는 사용할 수 없습니다.")
            action.accept(p); NativeJournalImpl.complete[Void](future, null)
          } catch {
            case e: Rejected => future.completeExceptionally(e); ()
            case e: RuntimeException => future.completeExceptionally(e); throw e
          }
        }))
      } catch { case e: RuntimeException => future.completeExceptionally(e); () }
      future
    }
  }

  override def speed(id: UUID, level: Double, mode: LinkApi.MovementMode): CompletionStage[Void] = {
    Objects.requireNonNull(mode); speedValue(level, false)
    run(id, (p: Player) => { val fly = (mode eq LinkApi.MovementMode.FLY) || ((mode eq LinkApi.MovementMode.AUTO) && p.isFlying()); if (fly) p.setFlySpeed(speedValue(level, true)) else p.setWalkSpeed(speedValue(level, false)) })
  }

  override def flight(id: UUID, enabled: java.lang.Boolean): CompletionStage[Void] = run(id, (p: Player) => {
    if (p.getGameMode() eq GameMode.SPECTATOR) throw new Rejected("관전자 모드에서는 비행을 변경할 수 없습니다.")
    val next: Boolean = if (enabled != null) enabled.booleanValue() else !p.getAllowFlight()
    if (!next && (p.getGameMode() eq GameMode.CREATIVE)) throw new Rejected("크리에이티브 모드의 비행 권한은 끌 수 없습니다.")
    if (next) { p.setAllowFlight(true); p.setFlying(true) } else { p.setFlying(false); p.setAllowFlight(false); p.setFallDistance(0) }
    p.getPersistentDataContainer().set(FLIGHT, org.bukkit.persistence.PersistentDataType.BYTE, java.lang.Byte.valueOf((if (next) 1 else 0).toByte))
  })

  override def heal(id: UUID): CompletionStage[Void] = run(id, (p: Player) => { val max = p.getAttribute(Attribute.MAX_HEALTH); if (max == null) throw new Rejected("최대 체력을 확인할 수 없습니다."); p.setHealth(max.getValue()); p.setFireTicks(0) })
  override def feed(id: UUID): CompletionStage[Void] = run(id, (p: Player) => { p.setFoodLevel(20); p.setSaturation(20); p.setExhaustion(0) })
}

object LinkUtilities {
  @scala.annotation.static final val FLIGHT: NamespacedKey = new NamespacedKey("link", "utility-flight")

  final class Rejected private[server] (message: String) extends IllegalArgumentException(message)

  def speedValue(level: Double, fly: Boolean): Float = {
    if (!java.lang.Double.isFinite(level) || level < 0 || level > 10) throw new Rejected("속도는 0~10 사이의 숫자 또는 초기화/reset을 입력하세요.")
    val base = if (fly) 0.1 else 0.2
    (if (level <= 1) level * base else base + (level - 1) * (1 - base) / 9).toFloat
  }

  /** Vanilla game-mode loading resets survival flight; restore only Link's explicit preference. */
  def restoreFlight(p: Player): Unit = {
    if ((p.getGameMode() eq GameMode.SURVIVAL) || (p.getGameMode() eq GameMode.ADVENTURE)) {
      val value: java.lang.Byte = p.getPersistentDataContainer().get(FLIGHT, org.bukkit.persistence.PersistentDataType.BYTE)
      if (value != null) {
        if (value.byteValue() == 1) { p.setAllowFlight(true); p.setFlying(true) }
        else if (value.byteValue() == 0) { p.setFlying(false); p.setAllowFlight(false) }
      }
    }
  }
}
