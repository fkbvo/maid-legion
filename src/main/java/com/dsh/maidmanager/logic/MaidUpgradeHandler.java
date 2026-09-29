package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Wires stored upgrade levels into the world: attribute modifiers on load, outgoing damage, and
 * bonus experience.
 *
 * <p>Scoped to <em>enrolled</em> maids throughout. The panel only ever shows maids the player
 * opted into, so an upgrade that quietly buffed a maid the panel does not list would be a bug
 * the player could not even see.
 */
@Mod.EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class MaidUpgradeHandler {

    private MaidUpgradeHandler() {
    }

    /**
     * Re-derives a maid's attribute modifiers whenever she enters a level.
     *
     * <p>One hook covers every way a maid can come back: released from our storage, revived from a
     * death record, force-load summoned, or simply loaded with her chunk. Attribute modifiers are
     * transient, so without this pass a restart would quietly strip every upgrade.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.getServer() == null) {
            return;
        }
        if (!MaidRegistry.get(owner.getServer()).isEnrolled(owner.getUUID(), maid.getUUID())) {
            return;
        }
        MaidProgressionService.applyTo(maid);
    }

    /**
     * Scales the damage a maid deals, by her own damage upgrade.
     *
     * <p><b>Why {@code LivingHurtEvent} and not TLM's {@code MaidAttackEvent}.</b> That event
     * exposes only {@code getAmount()} with no setter, so melee damage cannot be changed there.
     * This event is fired on the victim with a mutable amount, and covers every source that
     * attributes back to the maid.
     *
     * <p><b>Coverage.</b> Resolving the attacker through {@code Projectile.getOwner()} means one
     * rule handles melee, arrows, danmaku and gunfire alike. TaCZ's bullet is an
     * {@code EntityKineticBullet extends Projectile}, which is why guns need no special case and
     * no dependency on TaCZ. Spells are covered too as long as their damage source names the
     * caster - true damage that bypasses the damage pipeline entirely is a known gap.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        EntityMaid attacker = attackerOf(event.getSource());
        if (attacker == null || attacker == event.getEntity()) {
            // No maid involved, or she somehow damaged herself - never scale that.
            return;
        }
        if (attacker.getServer() == null) {
            return;
        }
        try {
            if (!(attacker.getOwner() instanceof ServerPlayer owner) || owner.getServer() == null) {
                return;
            }
            if (!MaidRegistry.get(owner.getServer())
                    .isEnrolled(owner.getUUID(), attacker.getUUID())) {
                return;
            }
            float multiplier = MaidUpgradeEffects.damageMultiplier(MaidProgressStorage
                    .get(owner.getServer()).levelsOf(attacker.getUUID()));
            if (multiplier > 1.0F) {
                event.setAmount(event.getAmount() * multiplier);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not apply the damage upgrade for maid {}",
                    attacker.getUUID(), t);
        }
    }

    /**
     * The enrolled maid who caused this damage, or null.
     *
     * <p>Both the direct entity and the causing entity are checked: a melee hit names the maid
     * directly, while an arrow or bullet names the projectile and only the projectile knows its
     * owner.
     */
    private static EntityMaid attackerOf(DamageSource source) {
        EntityMaid maid = asMaid(source.getDirectEntity());
        return maid != null ? maid : asMaid(source.getEntity());
    }

    private static EntityMaid asMaid(Entity entity) {
        if (entity instanceof EntityMaid maid) {
            return maid;
        }
        if (entity instanceof Projectile projectile && projectile.getOwner() instanceof EntityMaid maid) {
            return maid;
        }
        return null;
    }

    /**
     * Doubles (or more) the experience a maid gains from orbs, when the legion has the ability.
     *
     * <p>The bonus is computed from the orb's own value rather than from what TLM chose to grant,
     * so it stays predictable even if their pickup maths changes. Simulated pickups are skipped:
     * TLM posts those to ask whether a pickup would be allowed, and paying out on them would grant
     * the experience twice.
     */
    @SubscribeEvent
    public static void onPickupExperience(MaidPickupEvent.ExperienceResult event) {
        if (event.isSimulate() || !event.isCanPickup()) {
            return;
        }
        float fraction = experienceBonusFraction(event.getMaid());
        if (fraction <= 0.0F) {
            return;
        }
        int bonus = Math.round(event.getExperienceOrb().getValue() * fraction);
        if (bonus > 0) {
            EntityMaid maid = event.getMaid();
            maid.setExperience(maid.getExperience() + bonus);
        }
    }

    /**
     * The same bonus applied to P-point pickups.
     *
     * <p>A maid converts a P-point into {@code value / 4} experience, so that is the base the
     * bonus scales from.
     */
    @SubscribeEvent
    public static void onPickupPowerPoint(MaidPickupEvent.PowerPointResult event) {
        if (event.isSimulate() || !event.isCanPickup()) {
            return;
        }
        float fraction = experienceBonusFraction(event.getMaid());
        if (fraction <= 0.0F) {
            return;
        }
        int bonus = Math.round(event.getPowerPoint().getValue() / 4.0F * fraction);
        if (bonus > 0) {
            EntityMaid maid = event.getMaid();
            maid.setExperience(maid.getExperience() + bonus);
        }
    }

    /** 1.0 when the legion owns the experience ability, otherwise 0.0. */
    private static float experienceBonusFraction(EntityMaid maid) {
        try {
            if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.getServer() == null) {
                return 0.0F;
            }
            if (!MaidRegistry.get(owner.getServer()).isEnrolled(owner.getUUID(), maid.getUUID())) {
                return 0.0F;
            }
            return MaidProgressionService.hasAbility(owner, GlobalUpgrade.EXP_BONUS) ? 1.0F : 0.0F;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not apply the experience ability for maid {}",
                    maid.getUUID(), t);
            return 0.0F;
        }
    }
}
