package io.linkmc.server

import net.minecraft.nbt.{CompoundTag, ListTag, NbtAccounter, NbtIo}
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.{TagValueInput, TagValueOutput}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import scala.util.Using

/** Player NBT plus state omitted by vanilla player.dat (cursor, transient menu, stats, advancements). */
object NativePlayerCodec {
  @throws[Exception]
  private[server] def encode(player: ServerPlayer): Array[Byte] =
    compress(capture(player))

  /** Only called on the server thread; returned NBT owns all mutable arrays. */
  @throws[Exception]
  private[server] def capture(player: ServerPlayer): CompoundTag =
    Using.resource(new ProblemReporter.ScopedCollector(player.problemPath(), net.minecraft.server.MinecraftServer.LOGGER)) { reporter =>
      val output: TagValueOutput = TagValueOutput.createWithContext(reporter, player.registryAccess())
      player.saveWithoutId(output)
      // These entities are part of the same SRF world snapshot; never spawn a second copy from player NBT.
      val tag: CompoundTag = output.buildResult(); tag.remove("RootVehicle"); tag.remove("ender_pearls")
      tag.putString("LinkStats", player.getStats().linkSnapshot().toString())
      tag.putString("LinkAdvancements", player.getAdvancements().linkSnapshot().toString())
      val cursor: TagValueOutput = TagValueOutput.createWithContext(reporter, player.registryAccess())
      cursor.store("item", ItemStack.OPTIONAL_CODEC, player.containerMenu.getCarried()); tag.put("LinkCursor", cursor.buildResult())
      // Snapshot only non-player, non-block menu inventories. On reconnect they are recovered
      // into the player's inventory; result slots are derived and must never be duplicated.
      val recovery = new ListTag()
      val seen: java.util.Map[net.minecraft.world.Container, java.util.Set[Integer]] = new java.util.IdentityHashMap[net.minecraft.world.Container, java.util.Set[Integer]]()
      val menu = player.containerMenu
      val transientInputs: Boolean = menu.isInstanceOf[net.minecraft.world.inventory.AbstractCraftingMenu] ||
        menu.isInstanceOf[net.minecraft.world.inventory.ItemCombinerMenu] ||
        menu.isInstanceOf[net.minecraft.world.inventory.EnchantmentMenu] ||
        menu.isInstanceOf[net.minecraft.world.inventory.StonecutterMenu] ||
        menu.isInstanceOf[net.minecraft.world.inventory.LoomMenu] ||
        menu.isInstanceOf[net.minecraft.world.inventory.GrindstoneMenu] ||
        menu.isInstanceOf[net.minecraft.world.inventory.MerchantMenu]
      val slots = player.containerMenu.slots.iterator()
      while (slots.hasNext()) {
        val slot = slots.next()
        if (!transientInputs || slot.container.isInstanceOf[net.minecraft.world.inventory.ResultContainer]) ()
        else if (slot.isInstanceOf[net.minecraft.world.inventory.ResultSlot] || slot.isInstanceOf[net.minecraft.world.inventory.MerchantResultSlot]) ()
        else if ((slot.container eq player.getInventory()) || slot.container.isInstanceOf[net.minecraft.world.level.block.entity.BlockEntity] || slot.container.isInstanceOf[net.minecraft.world.entity.Entity]) ()
        else if (!seen.computeIfAbsent(slot.container, (key: net.minecraft.world.Container) => new java.util.HashSet[Integer]()).add(Integer.valueOf(slot.getContainerSlot())) || slot.getItem().isEmpty()) ()
        else {
          val item: TagValueOutput = TagValueOutput.createWithContext(reporter, player.registryAccess()); item.store("item", ItemStack.OPTIONAL_CODEC, slot.getItem()); recovery.add(item.buildResult())
        }
      }
      tag.put("LinkRecovery", recovery)
      tag.copy()
    }

  @throws[IOException]
  private[server] def compress(tag: CompoundTag): Array[Byte] = {
    val bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, bytes); bytes.toByteArray()
  }

  @throws[IOException]
  private[server] def decode(bytes: Array[Byte]): CompoundTag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(32L * 1024 * 1024))

  private[server] def recover(player: ServerPlayer, tag: CompoundTag): Unit =
    Using.resource(new ProblemReporter.ScopedCollector(player.problemPath(), net.minecraft.server.MinecraftServer.LOGGER)) { reporter =>
      val input = TagValueInput.create(reporter, player.registryAccess(), tag)
      input.child("LinkCursor").flatMap[ItemStack](v => v.read("item", ItemStack.OPTIONAL_CODEC)).ifPresent(item => give(player, item))
      val list = tag.getListOrEmpty("LinkRecovery")
      var i = 0
      while (i < list.size()) {
        TagValueInput.create(reporter, player.registryAccess(), list.getCompoundOrEmpty(i)).read("item", ItemStack.OPTIONAL_CODEC).ifPresent(item => give(player, item))
        i += 1
      }
    }

  private def give(player: ServerPlayer, item: ItemStack): Unit = {
    if (item.isEmpty()) return
    if (!player.getInventory().add(item)) player.drop(item, false, net.minecraft.util.Prediction.PREDICTED)
  }
}
