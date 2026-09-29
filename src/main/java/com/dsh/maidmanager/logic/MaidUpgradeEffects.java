package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitAttribute;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Map;
import java.util.UUID;

/**
 * Turns stored upgrade levels into live attribute modifiers on a maid.
 *
 * <p><b>Modifiers, never base values.</b> TLM's favour system reapplies
 * {@code setBaseValue(ATTACK_DAMAGE / MAX_HEALTH)} every time a maid gains a favour level, which
 * would silently wipe anything we wrote into the base value. An additive modifier sits on top of
 * the base instead, so a maxed maid keeps both her favour growth and her upgrades.
 *
 * <p><b>Idempotent by construction.</b> Each (maid, upgrade, attribute) pair maps to a stable
 * modifier id derived from those keys, and every apply pass removes that id before re-adding it.
 * So re-applying on every entity load cannot stack, and removing an upgrade (level back to 0)
 * simply removes the modifier.
 *
 * <p><b>Transient, not permanent.</b> The saved levels are the single source of truth. A
 * permanent modifier would be serialised into the maid's own NBT as well, and would then be
 * double-counted the next time we applied the stored level on top of it.
 *
 * <p>1.21 differences from the 1.20/Forge branch: attributes are addressed through
 * {@code Holder<Attribute>} rather than the raw {@code Attribute}, {@code AttributeModifier}
 * identifies itself with a {@code ResourceLocation} instead of a {@code UUID}, and the operation
 * constants were renamed.
 */
public final class MaidUpgradeEffects {

    private MaidUpgradeEffects() {
    }

    /**
     * The attributes one upgrade drives.
     *
     * <p>Empty for {@link MaidUpgrade#DAMAGE}, which is applied by an event rather than an
     * attribute, and an array of two for ranged speed because crossbows and guns are separate
     * attributes in TLM.
     *
     * <p>TLM's custom attributes are {@code DeferredHolder}s, which already implement
     * {@code Holder<Attribute>}, so they are passed through directly rather than unwrapped.
     */
    private static Holder<Attribute>[] targetsOf(MaidUpgrade upgrade) {
        return switch (upgrade) {
            case ATTACK -> new Holder[]{Attributes.ATTACK_DAMAGE};
            case HEALTH -> new Holder[]{Attributes.MAX_HEALTH};
            case ARMOR -> new Holder[]{Attributes.ARMOR};
            case SPEED -> new Holder[]{Attributes.MOVEMENT_SPEED};
            case LUCK -> new Holder[]{Attributes.LUCK};
            case PICKUP -> new Holder[]{InitAttribute.MAID_PICKUP_RANGE};
            case HUNGER -> new Holder[]{InitAttribute.MAID_HUNGER};
            case RANGED_SPEED -> new Holder[]{
                    InitAttribute.MAID_CROSSBOW_ATTACK_SPEED,
                    InitAttribute.MAID_GUN_ATTACK_SPEED};
            case DAMAGE -> new Holder[0];
        };
    }

    private static AttributeModifier.Operation operationOf(MaidUpgrade upgrade) {
        return upgrade.unit() == MaidUpgrade.Unit.PERCENT
                ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                : AttributeModifier.Operation.ADD_VALUE;
    }

    /**
     * Stable modifier id.
     *
     * <p>{@code index} separates the two attributes behind ranged speed, so they cannot collide.
     * The UUID's dashes are stripped because a {@code ResourceLocation} path only permits
     * {@code [a-z0-9_.-/]}.
     */
    private static ResourceLocation modifierId(UUID maidId, MaidUpgrade upgrade, int index) {
        return ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID,
                "upgrade_" + maidId.toString().replace("-", "") + "_" + upgrade.id() + "_" + index);
    }

    /**
     * Applies (or clears) every upgrade for one maid from her stored levels.
     *
     * <p>Safe to call repeatedly and safe to call on a maid with no upgrades at all - that pass
     * just removes any stale modifiers.
     */
    public static void apply(EntityMaid maid, Map<String, Integer> levels) {
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            Holder<Attribute>[] targets = targetsOf(upgrade);
            if (targets.length == 0) {
                continue;
            }
            int level = Math.min(levels.getOrDefault(upgrade.id(), 0), upgrade.maxLevel());
            for (int i = 0; i < targets.length; i++) {
                try {
                    AttributeInstance instance = maid.getAttribute(targets[i]);
                    if (instance == null) {
                        // Attribute not registered on this entity type; nothing we can do.
                        continue;
                    }
                    ResourceLocation id = modifierId(maid.getUUID(), upgrade, i);
                    instance.removeModifier(id);
                    if (level > 0) {
                        instance.addTransientModifier(new AttributeModifier(id,
                                upgrade.amountAt(level), operationOf(upgrade)));
                    }
                } catch (Throwable t) {
                    MaidManagerMod.LOGGER.error("Could not apply upgrade {} to maid {}",
                            upgrade.id(), maid.getUUID(), t);
                }
            }
        }
    }

    /**
     * The accumulated outgoing-damage multiplier for one maid's stored levels.
     *
     * <p>Kept here rather than in the event handler so the number and the upgrade definition stay
     * together. Returns 1.0 for a maid with no damage upgrade.
     */
    public static float damageMultiplier(Map<String, Integer> levels) {
        int level = levels.getOrDefault(MaidUpgrade.DAMAGE.id(), 0);
        return 1.0F + (float) MaidUpgrade.DAMAGE.amountAt(level);
    }
}
