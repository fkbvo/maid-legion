package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.network.MaidActionHandler;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTombstoneEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns an enrolled maid's death into a panel-managed revive instead of a tombstone.
 *
 * <p>Two behaviours are wanted, and they are easy to confuse, so they are separated:
 *
 * <ol>
 *   <li><b>Her items must not spill and must not be lost.</b> Nothing she carries should reach
 *       the ground, and a revive must put all of it back.</li>
 *   <li><b>No tombstone for enrolled maids.</b> The panel is the recovery path, so a duplicate
 *       tombstone would be a second, conflicting way back.</li>
 * </ol>
 *
 * <p><b>The trap this class exists to avoid, and fell into once.</b>
 * {@code EntityMaid.dropCustomDeathLoot} moves her whole inventory into an
 * {@link EntityTombstone} <em>before</em> it posts {@code MaidTombstoneEvent}:
 *
 * <pre>
 * CombinedInvWrapper inv = new CombinedInvWrapper(armor, hands, maidInv, maidBauble, hide, task);
 * for (int i = 0; i &lt; inv.getSlots(); i++) {
 *    tombstone.insertItem(inv.extractItem(i, inv.getSlotLimit(i), false));   // items leave her
 * }
 * ItemStack film = ItemFilm.maidToFilm(this);
 * if (MinecraftForge.EVENT_BUS.post(new MaidTombstoneEvent(this, tombstone))) return;
 * </pre>
 *
 * <p>So an event listener sees a maid who is <em>already empty</em>, and cancelling the event
 * strands the extracted stacks inside a tombstone that never reaches the world. An earlier
 * version of this class snapshotted there and then discarded that tombstone, which destroyed her
 * equipment outright. Snapshotting at {@link LivingDeathEvent} instead is correct by
 * construction: {@code LivingEntity.die} posts it before {@code dropAllDeathLoot}, so she is
 * still whole. See the note on {@link #onLivingDeath}.
 *
 * <p>Nothing here touches maids the player never enrolled - those keep vanilla TLM behaviour,
 * tombstones and all.
 */
@EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class MaidDeathHandler {

    /**
     * Snapshots taken at death time, waiting for TLM's tombstone event to arrive.
     *
     * <p>Keyed by maid UUID and overwritten on each death, so it cannot grow without bound. An
     * entry is consumed by {@link #onMaidTombstone}; if another mod cancels the death (a totem,
     * say) the stale entry is simply replaced the next time she dies.
     */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private MaidDeathHandler() {
    }

    /** Her full NBT plus who owns her, taken while she is still intact. */
    private record Pending(UUID owner, String name, CompoundTag data, boolean withItems) {
    }

    /**
     * Captures the maid while she still has everything.
     *
     * <p>{@code LivingEntity.die} calls {@code ForgeHooks.onLivingDeath} at its very first
     * instructions and only calls {@code dropAllDeathLoot} much later, so a listener here runs
     * before TLM touches her inventory. Verified against the 1.20.1 bytecode rather than assumed,
     * because the previous version of this class guessed the ordering wrong and lost items.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid) || maid.level().isClientSide()) {
            return;
        }
        Player owner = maid.getOwner() instanceof Player p ? p : null;
        if (owner == null || owner.getServer() == null) {
            return;
        }
        try {
            if (!MaidRegistry.get(owner.getServer()).isEnrolled(owner.getUUID(), maid.getUUID())) {
                return;
            }
            CompoundTag data = new CompoundTag();
            // Armour, both hands, and her four inventories, plus experience and favour.
            maid.saveWithoutId(data);
            // Curios live in a capability, not in her NBT, so they need fetching separately.
            boolean hadCurios = CuriosAccess.captureInto(maid, data);

            PENDING.put(maid.getUUID(), new Pending(owner.getUUID(),
                    maid.getDisplayName().getString(), data, hasAnyItem(maid) || hadCurios));
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not snapshot maid {} at death", maid.getUUID(), t);
        }
    }

    /** Replaces the tombstone with a panel revive record for enrolled maids. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMaidTombstone(MaidTombstoneEvent event) {
        EntityMaid maid = event.getMaid();
        if (maid.level().isClientSide()) {
            return;
        }
        Player owner = maid.getOwner() instanceof Player p ? p : null;
        if (owner == null || owner.getServer() == null) {
            // No owner means no roster to revive her from; let TLM do its normal thing.
            return;
        }
        if (!MaidRegistry.get(owner.getServer()).isEnrolled(owner.getUUID(), maid.getUUID())) {
            return;
        }

        try {
            MaidDeathStorage storage = MaidDeathStorage.get(owner.getServer());
            Pending pending = PENDING.remove(maid.getUUID());

            // TLM runs this method twice per death: once from dropAllDeathLoot, and again from
            // onRemovedFromWorld, because the early return above never sets its alreadyDropped
            // flag. The second pass must not record a second death, nor overwrite the good
            // snapshot with the now-empty maid.
            boolean alreadyRecorded = storage.contains(owner.getUUID(), maid.getUUID());
            if (!alreadyRecorded) {
                CompoundTag data = pending != null ? pending.data() : fallbackSnapshot(maid);
                String name = pending != null ? pending.name()
                        : maid.getDisplayName().getString();
                boolean withItems = pending != null && pending.withItems();
                storage.recordDeath(owner.getUUID(), maid.getUUID(), data, name, withItems);
                MaidManagerMod.LOGGER.info("Captured maid {} for panel revive ({} death(s))",
                        maid.getUUID(), storage.deathCount(owner.getUUID(), maid.getUUID()));
                if (owner instanceof ServerPlayer serverPlayer) {
                    serverPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "message.touhou_maid_legion.died_captured", maid.getDisplayName()),
                            false);
                }
            }

            // The tombstone holds stacks TLM pulled off her; they are already duplicated in our
            // snapshot, so the entity must never reach the world.
            clearTombstone(event.getTombstone());
            event.setCanceled(true);

            if (owner instanceof ServerPlayer serverPlayer) {
                MaidActionHandler.refresh(serverPlayer);
            }
        } catch (Throwable t) {
            // Never let a capture failure strand the maid with no tombstone at all - falling
            // through means TLM proceeds normally and the player still gets her items back.
            MaidManagerMod.LOGGER.error("Failed to capture maid {} on death; "
                    + "falling back to a normal tombstone", maid.getUUID(), t);
        }
    }

    /**
     * Holds back anything that would still have hit the ground.
     *
     * <p>Curios are taken earlier and TLM's tombstone never spawns, so this is normally empty -
     * but it is the guarantee behind "nothing drops", and it covers any other mod that adds death
     * loot for a maid. The stacks are copied out before the event is cancelled, because a
     * cancelled event discards the item entities that were built for it.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid) || maid.level().isClientSide()) {
            return;
        }
        Player owner = maid.getOwner() instanceof Player p ? p : null;
        if (owner == null || owner.getServer() == null) {
            return;
        }
        MaidDeathStorage storage = MaidDeathStorage.get(owner.getServer());
        if (!storage.contains(owner.getUUID(), maid.getUUID())) {
            // Not a death we took over, so it is not ours to interfere with.
            return;
        }
        try {
            // 1.21 replaced ItemStack.save(CompoundTag) with a registry-aware overload, because
            // the item id and its components resolve through the registries now.
            HolderLookup.Provider registries = owner.getServer().registryAccess();
            ListTag held = new ListTag();
            for (ItemEntity drop : event.getDrops()) {
                ItemStack stack = drop.getItem();
                if (!stack.isEmpty()) {
                    held.add(stack.save(registries));
                }
            }
            if (!held.isEmpty()) {
                storage.holdBackItems(owner.getUUID(), maid.getUUID(), held);
                MaidManagerMod.LOGGER.info("Held back {} drop stack(s) for maid {}",
                        held.size(), maid.getUUID());
            }
            event.setCanceled(true);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not hold back drops for maid {}",
                    maid.getUUID(), t);
        }
    }

    /**
     * Last-resort snapshot for a death that never passed through {@link #onLivingDeath}.
     *
     * <p>Her inventories have already been moved to the tombstone by this point, so this record
     * carries her identity and metadata but an empty inventory. Logged loudly, because it means
     * the ordering assumption above did not hold for this death.
     */
    private static CompoundTag fallbackSnapshot(EntityMaid maid) {
        MaidManagerMod.LOGGER.warn("Maid {} died without a death-time snapshot; "
                + "her inventory could not be preserved", maid.getUUID());
        CompoundTag data = new CompoundTag();
        try {
            maid.saveWithoutId(data);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Fallback snapshot failed for {}", maid.getUUID(), t);
        }
        return data;
    }

    /**
     * Discards a tombstone that will never reach the world.
     *
     * <p>Its contents are already duplicated in our snapshot, so nothing is lost by dropping the
     * entity. Its handler is a plain in-memory {@code ItemStackHandler} on an entity that was
     * never added to a level.
     */
    private static void clearTombstone(EntityTombstone tombstone) {
        try {
            tombstone.discard();
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.debug("Could not discard tombstone: {}", t.toString());
        }
    }

    /** True when the maid is carrying anything worth preserving. */
    private static boolean hasAnyItem(EntityMaid maid) {
        try {
            var inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (!inv.getStackInSlot(i).isEmpty()) {
                    return true;
                }
            }
            var bauble = maid.getMaidBauble();
            for (int i = 0; i < bauble.getSlots(); i++) {
                if (!bauble.getStackInSlot(i).isEmpty()) {
                    return true;
                }
            }
            for (ItemStack stack : maid.getHandSlots()) {
                if (!stack.isEmpty()) {
                    return true;
                }
            }
            for (ItemStack stack : maid.getArmorSlots()) {
                if (!stack.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.debug("Could not inspect maid inventory: {}", t.toString());
        }
        return false;
    }

    /** Reads the held-back stacks out of a captured maid's NBT. */
    public static ListTag extraItemsOf(CompoundTag data) {
        return data.getList(MaidDeathStorage.EXTRA_ITEMS_TAG, Tag.TAG_COMPOUND);
    }
}
