package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Saves and restores a maid's Curios inventory.
 *
 * <p><b>Why this has to exist at all.</b> Curios are not part of the maid's own NBT - her save
 * data carries only {@code MaidInventory}, {@code MaidBaubleInventory}, {@code MaidHideInventory}
 * and {@code MaidTaskInventory}. So a death snapshot taken through {@code saveWithoutId} silently
 * misses every trinket.
 *
 * <p>TLM does handle them, but only in its own listener, which sits at
 * {@code EventPriority.LOWEST} behind an {@code if (!event.isCanceled())} guard. Because this mod
 * cancels the tombstone event at {@code HIGHEST}, that guard skips the whole extraction, the
 * curios stay on the maid, and Curios then spills them on the ground when she dies.
 *
 * <p>Taking them here instead - at death time, before anything is moved - means they are off her
 * before Curios can drop them, and they can be put back in the same slots afterwards, because
 * {@code ICurioStacksHandler.serializeNBT} keeps slot indices.
 *
 * <p><b>Reflection on purpose.</b> Curios is optional; a player without it must still be able to
 * build and run this mod, so it must not become a compile dependency. Every entry point here
 * degrades to a no-op when Curios is absent or its API shifts.
 */
public final class CuriosAccess {

    private static final String API_CLASS = "top.theillusivec4.curios.api.CuriosApi";
    private static final String HANDLER_CLASS =
            "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";
    private static final String STACKS_HANDLER_CLASS =
            "top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler";
    private static final String DYNAMIC_HANDLER_CLASS =
            "top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler";

    /** Key under which the serialized curios travel inside the maid's captured NBT. */
    public static final String CURIOS_TAG = "MaidLegionCurios";

    private CuriosAccess() {
    }

    /** Whether Curios is installed. Checked once; the classpath does not change at runtime. */
    private static Boolean available;

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName(API_CLASS);
                available = Boolean.TRUE;
            } catch (Throwable t) {
                available = Boolean.FALSE;
            }
        }
        return available;
    }

    /**
     * Serializes every curio into {@code out} and empties the live slots.
     *
     * <p>Emptying matters as much as saving: a curio left on the maid is one Curios will drop
     * when it handles her death, which is the spill this is meant to stop.
     *
     * @return true when something was actually captured
     */
    public static boolean captureInto(EntityMaid maid, CompoundTag out) {
        if (!isAvailable()) {
            return false;
        }
        try {
            Map<?, ?> curios = curiosOf(maid);
            if (curios == null || curios.isEmpty()) {
                return false;
            }
            Class<?> stacksHandler = Class.forName(STACKS_HANDLER_CLASS);
            Method serialize = stacksHandler.getMethod("serializeNBT");
            Method getStacks = stacksHandler.getMethod("getStacks");
            Class<?> dynamic = Class.forName(DYNAMIC_HANDLER_CLASS);
            Method getSlots = dynamic.getMethod("getSlots");
            Method setSlot = dynamic.getMethod("setStackInSlot", int.class, ItemStack.class);

            CompoundTag saved = new CompoundTag();
            for (Map.Entry<?, ?> entry : curios.entrySet()) {
                String slotId = String.valueOf(entry.getKey());
                Object handler = entry.getValue();
                saved.put(slotId, (CompoundTag) serialize.invoke(handler));

                Object stacks = getStacks.invoke(handler);
                int slots = (int) getSlots.invoke(stacks);
                for (int i = 0; i < slots; i++) {
                    setSlot.invoke(stacks, i, ItemStack.EMPTY);
                }
            }
            out.put(CURIOS_TAG, saved);
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not capture curios; they may drop on death", t);
            return false;
        }
    }

    /**
     * Puts the curios back into the slots they came from.
     *
     * @return true when something was restored
     */
    public static boolean restoreInto(EntityMaid maid, CompoundTag from) {
        if (!isAvailable()) {
            return false;
        }
        CompoundTag saved = from.getCompound(CURIOS_TAG);
        if (saved.isEmpty()) {
            return false;
        }
        try {
            Map<?, ?> curios = curiosOf(maid);
            if (curios == null) {
                return false;
            }
            Class<?> stacksHandler = Class.forName(STACKS_HANDLER_CLASS);
            Method deserialize = stacksHandler.getMethod("deserializeNBT", CompoundTag.class);
            for (String slotId : saved.getAllKeys()) {
                Object handler = curios.get(slotId);
                if (handler != null) {
                    deserialize.invoke(handler, saved.getCompound(slotId));
                }
            }
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Could not restore curios for maid {}", maid.getUUID(), t);
            return false;
        }
    }

    /** The maid's curio slot-id to handler map, or null when she has none. */
    private static Map<?, ?> curiosOf(EntityMaid maid) throws Exception {
        Class<?> api = Class.forName(API_CLASS);
        Object lazy = api.getMethod("getCuriosInventory",
                net.minecraft.world.entity.LivingEntity.class).invoke(null, maid);
        if (!(lazy instanceof net.minecraftforge.common.util.LazyOptional<?> optional)) {
            return null;
        }
        Object handler = optional.orElse(null);
        if (handler == null) {
            return null;
        }
        Class<?> handlerClass = Class.forName(HANDLER_CLASS);
        Object curios = handlerClass.getMethod("getCurios").invoke(handler);
        return curios instanceof Map<?, ?> map ? map : null;
    }
}
