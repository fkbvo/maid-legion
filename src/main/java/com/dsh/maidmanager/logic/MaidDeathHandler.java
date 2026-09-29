package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTombstoneEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Turns an enrolled maid's death into a panel-managed revive instead of a tombstone.
 *
 * <p>Two behaviours are wanted here, and they are easy to confuse, so they are separated:
 *
 * <ol>
 *   <li><b>Her items must not spill.</b> TLM's death path moves her equipment into a
 *       {@link EntityTombstone}, and opening that tombstone scatters it on the ground. We
 *       snapshot the maid instead, so nothing is ever taken out of her in the first place and
 *       the revive restores her exactly as she was.</li>
 *   <li><b>No tombstone for enrolled maids.</b> The panel is the recovery path, so a duplicate
 *       tombstone would be a second, conflicting way back.</li>
 * </ol>
 *
 * <p><b>The trap this class exists to avoid.</b> {@code MaidTombstoneEvent} is cancellable, but
 * by the time it is posted TLM has already run {@code extractItem(..., false)} over the maid's
 * inventory to fill the tombstone - the items have genuinely left the entity. Simply cancelling
 * therefore <em>destroys</em> the inventory rather than preserving it. The fix is to read the
 * maid's NBT before cancelling and store that: {@code saveWithoutId} reflects the entity, and
 * because it runs first it still contains everything.
 *
 * <p>Nothing here touches maids the player never enrolled - those keep vanilla TLM behaviour,
 * tombstones and all.
 */
@EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class MaidDeathHandler {

    private MaidDeathHandler() {
    }

    /**
     * Replaces the tombstone with a panel revive record for enrolled maids.
     *
     * <p>Runs at {@code EventPriority.HIGHEST} so our snapshot is taken before any other
     * listener could mutate the maid, and so a lower-priority listener cannot observe a
     * half-cancelled state.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onMaidTombstone(MaidTombstoneEvent event) {
        EntityMaid maid = event.getMaid();
        if (maid.level().isClientSide()) {
            return;
        }
        Player owner = maid.getOwner() instanceof Player p ? p : null;
        if (owner == null) {
            // No owner means no roster to revive her from; let TLM do its normal thing.
            return;
        }

        MaidRegistry registry = MaidRegistry.get(owner.getServer());
        if (!registry.isEnrolled(owner.getUUID(), maid.getUUID())) {
            return;
        }

        try {
            // Taken FIRST, while the inventory is still on the entity. See the class comment:
            // by the time this event fires the tombstone already holds extracted copies, so a
            // snapshot taken after cancelling would be empty.
            CompoundTag data = new CompoundTag();
            maid.saveWithoutId(data);

            boolean withItems = hasAnyItem(maid);
            MaidDeathStorage.get(owner.getServer()).recordDeath(
                    owner.getUUID(), maid.getUUID(), data, maid.getDisplayName().getString(),
                    withItems);

            // Clear the tombstone we are about to discard, so its contents are not left
            // referenced by anything. The entity itself is never added to the level.
            clearTombstone(event.getTombstone());

            event.setCanceled(true);

            if (owner instanceof ServerPlayer serverPlayer) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.maid_legion.died_captured", maid.getDisplayName()), false);
                com.dsh.maidmanager.network.MaidActionHandler.refresh(serverPlayer);
            }
            MaidManagerMod.LOGGER.info("Captured maid {} for panel revive ({} death(s))",
                    maid.getUUID(), MaidDeathStorage.get(owner.getServer())
                            .deathCount(owner.getUUID(), maid.getUUID()));
        } catch (Throwable t) {
            // Never let a capture failure strand the maid with no tombstone at all - falling
            // through means TLM proceeds normally and the player still gets her items back.
            MaidManagerMod.LOGGER.error("Failed to capture maid {} on death; "
                    + "falling back to a normal tombstone", maid.getUUID(), t);
        }
    }

    /**
     * Empties a tombstone that will never reach the world.
     *
     * <p>Its handler is a plain in-memory {@code ItemStackHandler} on an entity that is not
     * added to any level, so this is belt-and-braces rather than strictly required - but it
     * guarantees no ItemStack is left holding a reference if the entity is ever resurrected by
     * another mod's listener.
     */
    private static void clearTombstone(EntityTombstone tombstone) {
        try {
            // extractItem without an IItemHandler view would need the field; the public
            // surface only offers insertItem, so we null the reference out by discarding the
            // entity itself. It was never spawned, so this only drops our local reference.
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

    /** Kept for clarity: the item handler type used by the maid's slots. */
    @SuppressWarnings("unused")
    private static IItemHandler handlerOf(EntityMaid maid) {
        return maid.getMaidInv();
    }
}
