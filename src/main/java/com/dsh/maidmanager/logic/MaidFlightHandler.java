package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets an enrolled maid follow her owner through the air while he is flying, and lets her keep
 * fighting while she does it.
 *
 * <p>Native behaviour is ground-only: the maid runs at the player's feet and is left behind the
 * moment he lifts off. This drives her directly instead of trying to give her a flying navigation
 * - TLM builds her {@code PathNavigation} in {@code createNavigation}, which we cannot replace
 * without patching TLM, so the maid is steered for the duration and handed back to her own AI once
 * neither condition applies.
 *
 * <p><b>Why the goal is not always the owner.</b> An earlier version steered at the owner every
 * tick, which meant a flying maid could no longer reach anything: TLM's melee behaviour only
 * attacks when {@code isWithinMeleeAttackRange}, closing that distance is the walk behaviour's
 * job, and that walk behaviour goes through the navigation we are holding down. The result was a
 * maid who followed perfectly and never swung. Steering at her <em>attack target</em> when she has
 * one is what gives her back her teeth - the attack itself needs proximity, not a completed path.
 *
 * <p><b>Control is taken, not shared.</b> Each tick the navigation is stopped and the delta
 * movement written directly. Leaving the navigation running would have two systems fighting over
 * the same entity, which shows up as a maid vibrating in mid-air.
 */
@Mod.EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class MaidFlightHandler {

    /** Beyond this the owner has flown off and chasing him would look like teleporting. */
    private static final double MAX_ENGAGE_DISTANCE = 32.0;

    /**
     * A looser leash while she is chasing something.
     *
     * <p>She has to be allowed to leave the owner's side to engage, but not to disappear over the
     * horizon, so this is wider than the follow range yet still finite.
     */
    private static final double MAX_CHASE_DISTANCE = 48.0;

    /** Close enough: stop pushing and let her hover where she is. */
    private static final double ARRIVE_DISTANCE = 3.0;

    /** Blocks per tick while following. */
    private static final double FOLLOW_SPEED = 0.35;

    /** Blocks per tick while closing on a target, a little quicker so fights actually happen. */
    private static final double CHASE_SPEED = 0.45;

    /** Vertical slack so a maid does not bob while matching a target's altitude. */
    private static final double VERTICAL_SLACK = 1.0;

    /**
     * When a maid's grace period ends, per maid.
     *
     * <p>Switching the ability off (or landing) must not drop her out of the sky, so gravity stays
     * off for a short while and she settles instead of falling.
     */
    private static final Map<UUID, Long> RELEASE_UNTIL = new ConcurrentHashMap<>();

    private MaidFlightHandler() {
    }

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (maid.level().isClientSide()) {
            return;
        }
        try {
            LivingEntity goal = goalFor(maid);
            if (goal == null) {
                release(maid);
                return;
            }
            assist(maid, goal, goal instanceof ServerPlayer ? FOLLOW_SPEED : CHASE_SPEED);
        } catch (Throwable t) {
            // Never let a movement quirk leave her floating: hand her back to normal gravity.
            release(maid);
            MaidManagerMod.LOGGER.error("Flight follow failed for maid {}", maid.getUUID(), t);
        }
    }

    /**
     * What this maid should be flying towards right now, or null when she should not be flying.
     *
     * <p>An attack target wins over the owner: following her master around while a monster chews
     * on her is not the behaviour anyone asked for.
     *
     * @return her attack target, her owner, or null
     */
    private static LivingEntity goalFor(EntityMaid maid) {
        if (!Config.COMMON.enableFlightFollow.get()) {
            return null;
        }
        if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.getServer() == null) {
            return null;
        }
        if (!MaidRegistry.get(owner.getServer()).isEnrolled(owner.getUUID(), maid.getUUID())) {
            return null;
        }
        // Owning the ability is not enough: a parked ability must do nothing at all.
        if (!MaidProgressionService.isAbilityActive(owner, GlobalUpgrade.FLIGHT)) {
            return null;
        }
        if (maid.isOrderedToSit()) {
            return null;
        }
        // Different dimension: the owner is not really "above" her at all.
        if (maid.level() != owner.level()) {
            return null;
        }

        LivingEntity target = attackTargetOf(maid);
        double ownerDistanceSq = maid.distanceToSqr(owner);
        if (target != null) {
            // Keep her roughly with the legion even while fighting.
            return ownerDistanceSq <= MAX_CHASE_DISTANCE * MAX_CHASE_DISTANCE ? target : null;
        }

        // mayfly is true for creative and spectator flight; onGround false means he is airborne.
        if (!owner.getAbilities().mayfly || owner.onGround()) {
            return null;
        }
        return ownerDistanceSq <= MAX_ENGAGE_DISTANCE * MAX_ENGAGE_DISTANCE ? owner : null;
    }

    /**
     * The living thing this maid has decided to attack, or null.
     *
     * <p>Read from the brain memory rather than {@code getTarget()}: TLM drives targeting through
     * {@code ATTACK_TARGET}, and that is also what its melee behaviour reads, so this is the same
     * signal the attack itself will use.
     */
    private static LivingEntity attackTargetOf(EntityMaid maid) {
        try {
            return maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                    .filter(LivingEntity::isAlive)
                    .orElse(null);
        } catch (Throwable t) {
            // A brain that is not running yet is not an error worth logging every tick.
            return null;
        }
    }

    private static void assist(EntityMaid maid, LivingEntity goal, double speed) {
        maid.setNoGravity(true);
        maid.getNavigation().stop();
        // Fighting or flying, being up here must not build up fall damage.
        maid.fallDistance = 0.0F;
        RELEASE_UNTIL.remove(maid.getUUID());

        Vec3 delta = goal.position().subtract(maid.position());
        double distance = delta.length();
        if (distance <= ARRIVE_DISTANCE) {
            // Hover, but keep matching altitude so she does not drift away.
            maid.setDeltaMovement(0.0D, verticalOnly(delta.y, speed), 0.0D);
            return;
        }
        Vec3 step = delta.normalize().scale(speed);
        // Snap altitude when close enough vertically, otherwise she oscillates around the target.
        double dy = Math.abs(delta.y) < VERTICAL_SLACK ? 0.0D : step.y;
        maid.setDeltaMovement(step.x, dy, step.z);
        maid.hasImpulse = true;
    }

    private static double verticalOnly(double dy, double speed) {
        if (Math.abs(dy) < VERTICAL_SLACK) {
            return 0.0D;
        }
        return Math.signum(dy) * Math.min(Math.abs(dy) * 0.25D, speed);
    }

    /**
     * Hands the maid back to her own AI, but not instantly to gravity.
     *
     * <p>Cutting flight the moment the ability is switched off would drop her from wherever she
     * happens to be, which is a nasty surprise and can kill her. She keeps hovering for
     * {@code flightReleaseGraceTicks} and only then falls.
     */
    private static void release(EntityMaid maid) {
        long now = maid.level().getGameTime();
        int grace = Config.COMMON.flightReleaseGraceTicks.get();
        Long until = RELEASE_UNTIL.get(maid.getUUID());
        if (until == null) {
            if (grace <= 0) {
                dropGravity(maid);
                return;
            }
            RELEASE_UNTIL.put(maid.getUUID(), now + grace);
            return;
        }
        if (now < until) {
            // Still easing down: keep her aloft and keep her from accumulating fall damage.
            maid.fallDistance = 0.0F;
            return;
        }
        RELEASE_UNTIL.remove(maid.getUUID());
        dropGravity(maid);
    }

    private static void dropGravity(EntityMaid maid) {
        if (maid.isNoGravity()) {
            maid.setNoGravity(false);
        }
    }
}
