package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.capability.PowerCapability;
import com.github.tartaricacid.touhoulittlemaid.capability.PowerCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Server-authoritative logic for upgrades, the P-point bank and opening a maid's own GUI.
 *
 * <p>Every entry point re-validates ownership and enrolment before touching anything. The client
 * only ever sends ids it saw in a snapshot, so a request for a maid the player does not control
 * means a stale client or a crafted packet - both are refused.
 */
public final class MaidProgressionService {

    /** Outcome of an attempted purchase, so the caller can pick the right message. */
    public enum UpgradeResult {
        OK,
        /** The feature is switched off server-side. */
        DISABLED,
        NOT_OWNER,
        NOT_ENROLLED,
        /** Her NBT is inside TLM's world data, which we cannot edit - she must be loaded first. */
        UNREACHABLE,
        MAX_LEVEL,
        NOT_ENOUGH_EXP,
        NOT_ENOUGH_POWER,
        UNKNOWN_UPGRADE
    }

    /** Outcome of asking to open a maid's GUI. */
    public enum OpenResult {
        OK,
        NOT_OWNER,
        NOT_ENROLLED,
        /** Not loaded and not stored, so there is no entity to bind the menu to. */
        NOT_LOADED,
        /**
         * She is loaded, but in another dimension. The client resolves the menu's entity by id in
         * the player's own level, so the container would open broken.
         */
        WRONG_DIMENSION
    }

    private MaidProgressionService() {
    }

    // ------------------------------------------------------------------
    // Reading and spending a maid's experience
    // ------------------------------------------------------------------

    /**
     * Her experience, wherever she currently lives.
     *
     * @return {@code -1} when she is in a state whose NBT we cannot reach
     */
    public static int readExperience(ServerPlayer player, UUID maidId) {
        EntityMaid loaded = MaidManagerService.findLoadedMaid(player, maidId);
        if (loaded != null) {
            return loaded.getExperience();
        }
        var stored = MaidStorage.get(player.getServer()).get(player.getUUID(), maidId);
        if (stored != null) {
            return stored.data().getInt(EntityMaid.EXPERIENCE_TAG);
        }
        var dead = MaidDeathStorage.get(player.getServer()).get(player.getUUID(), maidId);
        if (dead != null) {
            return dead.data().getInt(EntityMaid.EXPERIENCE_TAG);
        }
        return -1;
    }

    /**
     * Deducts {@code cost} from her experience, wherever she lives.
     *
     * <p>Refuses rather than going negative, and reports whether the write actually happened, so a
     * caller never upgrades a maid it failed to charge.
     */
    private static boolean spendExperience(ServerPlayer player, UUID maidId, int cost) {
        EntityMaid loaded = MaidManagerService.findLoadedMaid(player, maidId);
        if (loaded != null) {
            if (loaded.getExperience() < cost) {
                return false;
            }
            loaded.setExperience(loaded.getExperience() - cost);
            return true;
        }
        boolean[] charged = {false};
        if (MaidStorage.get(player.getServer()).editData(player.getUUID(), maidId, data -> {
            int have = data.getInt(EntityMaid.EXPERIENCE_TAG);
            if (have >= cost) {
                data.putInt(EntityMaid.EXPERIENCE_TAG, have - cost);
                charged[0] = true;
            }
        })) {
            return charged[0];
        }
        if (MaidDeathStorage.get(player.getServer()).editData(player.getUUID(), maidId, data -> {
            int have = data.getInt(EntityMaid.EXPERIENCE_TAG);
            if (have >= cost) {
                data.putInt(EntityMaid.EXPERIENCE_TAG, have - cost);
                charged[0] = true;
            }
        })) {
            return charged[0];
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Per-maid upgrades
    // ------------------------------------------------------------------

    /** Buys one level of a per-maid upgrade, paid from that maid's own experience. */
    public static UpgradeResult buyMaidUpgrade(ServerPlayer player, UUID maidId, String upgradeId) {
        if (!Config.COMMON.enableUpgrades.get()) {
            return UpgradeResult.DISABLED;
        }
        MaidUpgrade upgrade = MaidUpgrade.byId(upgradeId);
        if (upgrade == null) {
            return UpgradeResult.UNKNOWN_UPGRADE;
        }
        if (!MaidManagerService.ownsMaid(player, maidId)) {
            return UpgradeResult.NOT_OWNER;
        }
        if (!MaidManagerService.isEnrolled(player, maidId)) {
            return UpgradeResult.NOT_ENROLLED;
        }
        if (readExperience(player, maidId) < 0) {
            return UpgradeResult.UNREACHABLE;
        }
        MaidProgressStorage storage = MaidProgressStorage.get(player.getServer());
        int level = storage.level(maidId, upgrade);
        if (level >= upgrade.maxLevel()) {
            return UpgradeResult.MAX_LEVEL;
        }
        int cost = upgrade.costFor(level);
        if (cost <= 0) {
            return UpgradeResult.MAX_LEVEL;
        }
        // Charge first, then record: a failure to charge must never leave a free level behind.
        if (!spendExperience(player, maidId, cost)) {
            return UpgradeResult.NOT_ENOUGH_EXP;
        }
        storage.setLevel(maidId, upgrade, level + 1);
        applyTo(player, maidId);
        return UpgradeResult.OK;
    }

    // ------------------------------------------------------------------
    // Legion-wide abilities
    // ------------------------------------------------------------------

    /** Buys a one-off legion ability, paid from the player's banked P-points. */
    public static UpgradeResult buyGlobalUpgrade(ServerPlayer player, String upgradeId) {
        if (!Config.COMMON.enableUpgrades.get()) {
            return UpgradeResult.DISABLED;
        }
        GlobalUpgrade ability = GlobalUpgrade.byId(upgradeId);
        if (ability == null) {
            return UpgradeResult.UNKNOWN_UPGRADE;
        }
        MaidProgressStorage storage = MaidProgressStorage.get(player.getServer());
        if (storage.hasGlobal(player.getUUID(), ability)) {
            return UpgradeResult.MAX_LEVEL;
        }
        if (!storage.charge(player.getUUID(), ability.powerCost())) {
            return UpgradeResult.NOT_ENOUGH_POWER;
        }
        storage.setGlobalLevel(player.getUUID(), ability, 1);
        return UpgradeResult.OK;
    }

    // ------------------------------------------------------------------
    // Applying stored levels to a live maid
    // ------------------------------------------------------------------

    /** Re-derives a loaded maid's attribute modifiers from her stored levels. */
    public static void applyTo(EntityMaid maid) {
        try {
            MaidProgressStorage storage = MaidProgressStorage.get(maid.getServer());
            MaidUpgradeEffects.apply(maid, storage.levelsOf(maid.getUUID()));
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not apply upgrades to maid {}", maid.getUUID(), t);
        }
    }

    private static void applyTo(ServerPlayer player, UUID maidId) {
        EntityMaid loaded = MaidManagerService.findLoadedMaid(player, maidId);
        if (loaded != null) {
            applyTo(loaded);
        }
    }

    /** True when the player's legion has a given ability. */
    public static boolean hasAbility(ServerPlayer player, GlobalUpgrade ability) {
        return MaidProgressStorage.get(player.getServer()).hasGlobal(player.getUUID(), ability);
    }

    // ------------------------------------------------------------------
    // The P-point bank
    // ------------------------------------------------------------------

    private static PowerCapability walletCap(ServerPlayer player) {
        return player.getCapability(PowerCapabilityProvider.POWER_CAP, null).orElse(null);
    }

    /** The player's spendable TLM wallet balance, capped by TLM at 5.0. */
    public static float walletOf(ServerPlayer player) {
        PowerCapability power = walletCap(player);
        return power == null ? 0.0F : power.get();
    }

    public static float bankedOf(ServerPlayer player) {
        return MaidProgressStorage.get(player.getServer()).banked(player.getUUID());
    }

    public static float bankCap() {
        return (float) Config.COMMON.maxBankedPower.get().doubleValue();
    }

    /** Moves as much of the wallet as the bank will take. Returns the amount moved. */
    public static float depositAll(ServerPlayer player) {
        PowerCapability power = walletCap(player);
        if (power == null) {
            return 0.0F;
        }
        float wallet = power.get();
        float moved = MaidProgressStorage.get(player.getServer())
                .deposit(player.getUUID(), wallet, bankCap());
        if (moved > 0.0F) {
            power.min(moved);
        }
        return moved;
    }

    /** Moves banked points back into the wallet, bounded by the wallet's own headroom. */
    public static float withdrawAll(ServerPlayer player) {
        PowerCapability power = walletCap(player);
        if (power == null) {
            return 0.0F;
        }
        float room = PowerCapability.MAX_POWER - power.get();
        if (room <= 0.0F) {
            return 0.0F;
        }
        float taken = MaidProgressStorage.get(player.getServer())
                .withdraw(player.getUUID(), room);
        if (taken > 0.0F) {
            power.add(taken);
        }
        return taken;
    }

    public static void setAutoDeposit(ServerPlayer player, boolean enabled) {
        MaidProgressStorage.get(player.getServer())
                .setAutoDepositEnabled(player.getUUID(), enabled);
    }

    public static boolean autoDepositEnabled(ServerPlayer player) {
        return MaidProgressStorage.get(player.getServer()).autoDepositEnabled(player.getUUID());
    }

    /**
     * Sweeps the wallet into the bank so picked-up P-points have somewhere to go.
     *
     * <p><b>Why a poll and not a hook.</b> Nothing in TLM fires an event when a <em>player</em>
     * picks up a P-point; {@code EntityPowerPoint.playerTouch} calls straight into the capability,
     * and the overflow-to-vanilla-experience conversion is hard-coded there. Draining the wallet
     * down to {@code autoDepositThreshold} every second is therefore the only way to keep pickup
     * room free - and that is what actually raises the effective 5.0 ceiling.
     */
    public static void tickAutoDeposit(MinecraftServer server) {
        float threshold = (float) Config.COMMON.autoDepositThreshold.get().doubleValue();
        if (threshold >= PowerCapability.MAX_POWER) {
            return;
        }
        MaidProgressStorage storage = MaidProgressStorage.get(server);
        float bankCap = bankCap();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (!storage.autoDepositEnabled(player.getUUID())) {
                    continue;
                }
                PowerCapability power = walletCap(player);
                if (power == null) {
                    continue;
                }
                float wallet = power.get();
                if (wallet <= threshold) {
                    continue;
                }
                float moved = storage.deposit(player.getUUID(), wallet - threshold, bankCap);
                if (moved > 0.0F) {
                    power.min(moved);
                }
            } catch (Throwable t) {
                MaidManagerMod.LOGGER.error("Auto-deposit failed for {}", player.getUUID(), t);
            }
        }
    }

    // ------------------------------------------------------------------
    // Opening a maid's own GUI remotely
    // ------------------------------------------------------------------

    /**
     * Opens the maid's TLM GUI for the player, wherever the panel is.
     *
     * <p>TLM's {@code openMaidGui} itself checks nothing but "not sleeping", so ownership,
     * enrolment, reachability and same-dimension are all enforced here.
     */
    public static OpenResult openMaidGui(ServerPlayer player, UUID maidId) {
        if (!MaidManagerService.ownsMaid(player, maidId)) {
            return OpenResult.NOT_OWNER;
        }
        if (!MaidManagerService.isEnrolled(player, maidId)) {
            return OpenResult.NOT_ENROLLED;
        }
        // Convenience: a maid we are holding as data is released first, so the button works on
        // her too instead of demanding a separate summon.
        if (MaidStorage.get(player.getServer()).contains(player.getUUID(), maidId)) {
            MaidManagerService.releaseStored(player, maidId);
        }
        EntityMaid maid = MaidManagerService.findLoadedMaid(player, maidId);
        if (maid == null) {
            return OpenResult.NOT_LOADED;
        }
        if (maid.level() != player.level()) {
            return OpenResult.WRONG_DIMENSION;
        }
        return maid.openMaidGui(player) ? OpenResult.OK : OpenResult.NOT_LOADED;
    }

    // ------------------------------------------------------------------
    // TLM's death penalty, which our own snapshot would otherwise erase
    // ------------------------------------------------------------------

    /**
     * Re-applies TLM's death favour penalty to a maid we are bringing back.
     *
     * <p>Our death snapshot is taken from {@code LivingDeathEvent} at priority HIGHEST, while TLM
     * applies the penalty from its own listener at the default priority. The snapshot therefore
     * always predates the penalty, so restoring it would quietly hand back the points TLM just
     * took. This puts the penalty back, reduced or waived by the
     * {@link GlobalUpgrade#DEATH_FAVOR} ability.
     *
     * <p>The amount is read from TLM's own {@code Type.DEATH} rather than hard-coded, so a change
     * on their side carries over automatically.
     */
    public static void applyDeathPenalty(EntityMaid maid) {
        try {
            // getOwner() is typed LivingEntity on TamableAnimal, so the ServerPlayer test is the
            // cast - a maid owned by a non-player (or unowned) has nobody to bill.
            if (!(maid.getOwner() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (hasAbility(serverPlayer, GlobalUpgrade.DEATH_FAVOR)) {
                return;
            }
            int points = Type.DEATH.getPoint();
            if (points > 0) {
                // reduce() rather than setFavorability(): it runs TLM's own level-change handling,
                // so the health/attack the favour level grants stay consistent.
                maid.getFavorabilityManager().reduce(points);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not re-apply the death favour penalty to {}",
                    maid.getUUID(), t);
        }
    }

    /** Reads a stored snapshot's favour value, used to describe the penalty in the panel. */
    public static int deathPenaltyPoints() {
        try {
            return Math.max(0, Type.DEATH.getPoint());
        } catch (Throwable t) {
            return 2;
        }
    }

    /** Convenience for callers that only have a tag. */
    public static int experienceOf(CompoundTag data) {
        return data.getInt(EntityMaid.EXPERIENCE_TAG);
    }
}
