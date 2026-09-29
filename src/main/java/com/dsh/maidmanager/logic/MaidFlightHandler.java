package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Lets an enrolled maid follow her owner through the air while he is flying.
 *
 * <p>Native behaviour is ground-only: the maid runs at the player's feet and is left behind the
 * moment he lifts off. This drives her directly instead of trying to give her a flying navigation
 * - TLM builds her {@code PathNavigation} in {@code createNavigation}, which we cannot replace
 * without patching TLM, so the maid is steered for the duration of the flight and handed back to
 * her own AI the moment the owner lands.
 *
 * <p><b>Control is taken, not shared.</b> Each tick the maid's navigation is stopped and her
 * delta movement written directly. Leaving the navigation running would have two systems fighting
 * over the same entity, which shows up as a maid vibrating in mid-air.
 *
 * <p>Deliberately conservative about when it engages - see {@link #shouldAssist}. The alternative
 * is a maid yanked across the world the instant the player taps space.
 */
@EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class MaidFlightHandler {

    /** Beyond this the owner has flown off and chasing him would look like teleporting. */
    private static final double MAX_ENGAGE_DISTANCE = 32.0;

    /** Close enough: stop pushing and let her hover where she is. */
    private static final double ARRIVE_DISTANCE = 3.0;

    /** Blocks per tick while catching up. */
    private static final double BASE_SPEED = 0.35;

    /** Vertical slack so a maid does not bob while matching the owner's altitude. */
    private static final double VERTICAL_SLACK = 1.0;

    private MaidFlightHandler() {
    }

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (maid.level().isClientSide()) {
            return;
        }
        try {
            if (!Config.COMMON.enableFlightFollow.get()) {
                release(maid);
                return;
            }
            if (!shouldAssist(maid)) {
                release(maid);
                return;
            }
            assist(maid, (ServerPlayer) maid.getOwner());
        } catch (Throwable t) {
            // Never let a movement quirk leave her floating: hand her back to normal gravity.
            release(maid);
            MaidManagerMod.LOGGER.error("Flight follow failed for maid {}", maid.getUUID(), t);
        }
    }

    /**
     * Whether this maid should be flown right now.
     *
     * <p>Requires all of: the legion owns the ability, she is enrolled, she has an owner who is in
     * creative flight, she is not sitting, and she is close enough that following is plausible.
     */
    private static boolean shouldAssist(EntityMaid maid) {
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            return false;
        }
        if (owner.getServer() == null) {
            return false;
        }
        if (!MaidRegistry.get(owner.getServer()).isEnrolled(owner.getUUID(), maid.getUUID())) {
            return false;
        }
        if (!MaidProgressionService.hasAbility(owner, GlobalUpgrade.FLIGHT)) {
            return false;
        }
        // mayfly is true for creative and spectator flight; onGround false means he is airborne.
        if (!owner.getAbilities().mayfly || owner.onGround()) {
            return false;
        }
        if (maid.isOrderedToSit()) {
            return false;
        }
        // Different dimension: the owner is not really "above" her at all.
        if (maid.level() != owner.level()) {
            return false;
        }
        return maid.distanceToSqr(owner) <= MAX_ENGAGE_DISTANCE * MAX_ENGAGE_DISTANCE;
    }

    private static void assist(EntityMaid maid, ServerPlayer owner) {
        maid.setNoGravity(true);
        maid.getNavigation().stop();

        Vec3 target = new Vec3(owner.getX(), owner.getY(), owner.getZ());
        Vec3 delta = target.subtract(maid.position());
        double distance = delta.length();
        if (distance <= ARRIVE_DISTANCE) {
            // Hover, but keep matching his altitude so she does not drift away.
            maid.setDeltaMovement(0.0D, verticalOnly(delta.y), 0.0D);
            maid.fallDistance = 0.0F;
            return;
        }
        Vec3 step = delta.normalize().scale(BASE_SPEED);
        // Snap altitude when close enough vertically, otherwise she oscillates around his Y.
        double dy = Math.abs(delta.y) < VERTICAL_SLACK ? 0.0D : step.y;
        maid.setDeltaMovement(step.x, dy, step.z);
        maid.fallDistance = 0.0F;
        maid.hasImpulse = true;
    }

    private static double verticalOnly(double dy) {
        if (Math.abs(dy) < VERTICAL_SLACK) {
            return 0.0D;
        }
        return Math.signum(dy) * Math.min(Math.abs(dy) * 0.25D, BASE_SPEED);
    }

    /** Hands the maid back to her own AI and to gravity. */
    private static void release(EntityMaid maid) {
        if (maid.isNoGravity()) {
            maid.setNoGravity(false);
        }
    }
}
