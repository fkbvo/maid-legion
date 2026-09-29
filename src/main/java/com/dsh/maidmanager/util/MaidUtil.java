package com.dsh.maidmanager.util;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The single point of contact between this mod and Touhou Little Maid.
 *
 * <p>Everything TLM-specific is concentrated here on purpose. Facts verified against
 * TLM 1.5.3 (bytecode + source):
 *
 * <ul>
 *   <li>{@code MaidWorldData} is a {@code SavedData} singleton stored in the OVERWORLD's
 *       {@code DimensionDataStorage} under the id {@code touhou_little_maid_world_data}.
 *       There is exactly one instance per server, which is why cross-dimension lookups work.</li>
 *   <li>{@code EntityMaid.onRemovedFromWorld()} calls {@code addInfo(maid)} and
 *       {@code onAddedToWorld()} calls {@code removeInfo(maid)}. Therefore
 *       {@code getPlayerMaidInfos()} is an accurate list of maids that are <em>currently
 *       unloaded</em> - not a permanent roster.</li>
 *   <li>Storing/restoring a maid is exactly {@code saveWithoutId(tag)} / {@code load(tag)},
 *       as done by {@code ItemSmartSlab}.</li>
 * </ul>
 *
 * <p>Every reflected member is resolved once and cached; if a future TLM version renames
 * something, the affected feature reports itself unavailable rather than throwing.
 */
public final class MaidUtil {
    /** NBT key used by TLM's own store-maid items; we mirror it for tooltip compatibility. */
    public static final String MAID_INFO_TAG = "MaidInfo";

    private static Boolean tlmPresent;
    private static Method getMaidDataMethod;
    private static Method removeMaidInfoMethod;
    private static Method addMaidInfoMethod;

    private MaidUtil() {
    }

    /** True when TLM is loaded and the pieces we depend on are reachable. */
    public static boolean isTlmAvailable() {
        if (tlmPresent == null) {
            try {
                Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
                tlmPresent = true;
            } catch (Throwable t) {
                tlmPresent = false;
            }
        }
        return tlmPresent;
    }

    /**
     * Resolves optional/reflective entry points. Returns false if any hard requirement is
     * missing, in which case the caller should degrade instead of crashing.
     */
    public static boolean selfCheck() {
        if (!isTlmAvailable()) {
            return false;
        }
        boolean ok = true;
        try {
            getMaidDataMethod = MaidWorldData.class.getMethod("get", Level.class);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("MaidWorldData.get(Level) not found: {}", t.toString());
            ok = false;
        }
        try {
            removeMaidInfoMethod = MaidWorldData.class.getMethod("removeInfo", EntityMaid.class);
            addMaidInfoMethod = MaidWorldData.class.getMethod("addInfo", EntityMaid.class);
        } catch (Throwable t) {
            // Only used by the optional "unregister" path.
            MaidManagerMod.LOGGER.debug("MaidWorldData add/removeInfo not resolvable: {}", t.toString());
        }
        return ok;
    }

    /** {@code MaidWorldData.get(level)}, or null on the client or if TLM changed. */
    @Nullable
    public static MaidWorldData getWorldData(@Nullable Level level) {
        if (level == null || level.isClientSide()) {
            return null;
        }
        try {
            if (getMaidDataMethod == null) {
                getMaidDataMethod = MaidWorldData.class.getMethod("get", Level.class);
            }
            return (MaidWorldData) getMaidDataMethod.invoke(null, level);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("Failed to read MaidWorldData: {}", t.toString());
            return null;
        }
    }

    /**
     * Maids owned by {@code player} that are currently unloaded (their chunk is not loaded,
     * possibly because they are in another dimension). Never null.
     */
    public static List<MaidInfo> getUnloadedMaidInfos(ServerPlayer player) {
        MaidWorldData data = getWorldData(player.level());
        if (data == null) {
            return Collections.emptyList();
        }
        try {
            List<MaidInfo> infos = data.getPlayerMaidInfos(player);
            return infos == null ? Collections.emptyList() : infos;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("Failed to list unloaded maids: {}", t.toString());
            return Collections.emptyList();
        }
    }

    /** Same as {@link #getMaidDataMethod} but resolved per-call for safety. */
    @Nullable
    public static CompoundTag getStoredMaidTag(net.minecraft.world.item.ItemStack stack) {
        try {
            if (stack.isEmpty() || !stack.hasTag()) {
                return null;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains(MAID_INFO_TAG)) {
                return null;
            }
            CompoundTag info = tag.getCompound(MAID_INFO_TAG);
            return info.isEmpty() ? null : info;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Safety helpers: these mirror what TLM's own unload mixin checks.
    // ------------------------------------------------------------------

    /**
     * Mirrors {@code EntityMaid.canBrainMoving()} in TLM 1.5.3, which is
     * {@code !isMaidInSittingPose() && !isPassenger() && !isSleeping() && !isFallFlying()}.
     * TLM only teleports a maid home on chunk unload when this is true, so we clear the
     * same states before summoning to avoid fighting the AI.
     */
    public static void clearBlockingStates(EntityMaid maid) {
        try {
            if (maid.isPassenger()) {
                maid.stopRiding();
            }
            if (maid.isMaidInSittingPose()) {
                maid.setInSittingPose(false);
            }
            if (maid.isSleeping()) {
                maid.stopSleeping();
            }
            if (maid.isFallFlying()) {
                // 1.20.1 has no public "stopFallFlying". The fall-flying state is driven by
                // the entity's shared flag 7, which is protected, so we only zero the motion
                // here. teleportToOwner() still works; the pose clears on its own next tick.
                maid.setDeltaMovement(0.0D, 0.0D, 0.0D);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.debug("clearBlockingStates failed: {}", t.toString());
        }
    }

    /**
     * Disables TLM "home mode" so the maid is willing to travel. TLM's own servant bell
     * does {@code maid.setHomeModeEnable(false)} before teleporting for the same reason.
     */
    public static void disableHomeMode(EntityMaid maid) {
        try {
            if (maid.isHomeModeEnable()) {
                maid.setHomeModeEnable(false);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.debug("disableHomeMode failed: {}", t.toString());
        }
    }

    /** Human-readable version of a dimension id, e.g. {@code minecraft:the_nether}. */
    public static String dimensionOf(Entity entity) {
        return entity.level().dimension().location().toString();
    }

    /**
     * Removes the maid from TLM's "unloaded maids" record.
     *
     * <p>TLM adds an entry in {@code onRemovedFromWorld} and removes it in
     * {@code onAddedToWorld}. A maid we rebuild from captured NBT is added to the level
     * directly, which does fire {@code onAddedToWorld} - but calling this explicitly makes the
     * revive independent of that ordering, so a revived maid can never end up listed as both
     * alive and unloaded.
     */
    public static void registerMaid(EntityMaid maid) {
        try {
            if (removeMaidInfoMethod == null) {
                return;
            }
            MaidWorldData data = getWorldData(maid.level());
            if (data != null) {
                removeMaidInfoMethod.invoke(data, maid);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.debug("registerMaid failed: {}", t.toString());
        }
    }

    /** True when the given dimension id matches the level the player is in. */
    public static boolean isSameDimension(ServerPlayer player, String dimensionId) {
        return player.level().dimension().location().toString().equals(dimensionId);
    }

    /** Formats a maid's stored position for display. */
    public static Component describePosition(String dimensionId, BlockPos pos) {
        return Component.literal(dimensionId + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    /** Best-effort display name for an offline maid record. */
    @Nullable
    public static Component infoName(@Nullable MaidInfo info) {
        return info == null ? null : info.getName();
    }

    /** UUID of an offline maid record. */
    @Nullable
    public static UUID infoId(@Nullable MaidInfo info) {
        return info == null ? null : info.getEntityId();
    }

    /** Unused stub kept to make the reflective intent explicit for future TLM versions. */
    @Nullable
    public static Player ownerOf(EntityMaid maid) {
        return maid.getOwner() instanceof Player p ? p : null;
    }

    /** True when the maid currently sits in a loaded {@link ServerLevel}. */
    public static boolean isLoaded(@Nullable Entity entity) {
        return entity != null && !entity.isRemoved() && entity.level() instanceof ServerLevel;
    }
}
