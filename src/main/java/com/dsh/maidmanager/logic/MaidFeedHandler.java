package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Lets a player feed bottled experience to a maid.
 *
 * <p><b>Why this has to exist.</b> A maid only gains experience from orbs she picks up herself
 * ({@code EntityMaid.aiStep} scans for them) and from P-points. A thrown Bottle o' Enchanting
 * spawns ordinary orbs, and ordinary orbs are attracted to the nearest <em>player</em> - so the
 * person who threw it, standing right there, collects their own bottle and the maid gets nothing.
 * There was no way to hand a maid experience directly.
 *
 * <p>TLM's own glass-bottle interaction runs the other way: sneaking with an empty bottle in hand
 * <em>extracts</em> her experience into bottles at 12 per bottle. This handler deliberately mirrors
 * that rate, so the two directions are symmetric and cannot be used to launder experience.
 *
 * <p>Hooking: TLM posts {@link InteractMaidEvent} from {@code EntityMaid.mobInteract} before it
 * opens the maid GUI, and it is cancellable. Consuming it keeps the bottle from also being thrown.
 *
 * <p>1.21 difference from the 1.20/Forge branch: only the event-bus annotations changed
 * ({@code @EventBusSubscriber} instead of {@code @Mod.EventBusSubscriber}); the TLM event itself
 * is unchanged.
 */
@EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class MaidFeedHandler {

    /**
     * Experience granted per bottle.
     *
     * <p>Matches TLM's own extraction rate of 12, so feeding and extracting cancel out exactly.
     */
    public static final int EXPERIENCE_PER_BOTTLE = 12;

    private MaidFeedHandler() {
    }

    @SubscribeEvent
    public static void onInteractMaid(InteractMaidEvent event) {
        Player player = event.getPlayer();
        // The event fires on both sides; the maid's experience is server state.
        if (player.level().isClientSide()) {
            return;
        }
        ItemStack stack = event.getStack();
        if (stack.isEmpty() || stack.getItem() != Items.EXPERIENCE_BOTTLE) {
            return;
        }
        EntityMaid maid = event.getMaid();
        if (!maid.isOwnedBy(player)) {
            // Silently ignore: someone else's maid is not this gesture's business.
            return;
        }

        // Sneaking feeds the whole stack, mirroring how TLM's extraction treats the same gesture.
        int offer = player.isShiftKeyDown() ? stack.getCount() : 1;
        if (offer <= 0) {
            return;
        }
        int experience = offer * EXPERIENCE_PER_BOTTLE;
        stack.shrink(offer);
        maid.setExperience(maid.getExperience() + experience);

        player.displayClientMessage(Component.translatable(
                "message.touhou_maid_legion.fed_bottle", offer, experience), true);

        // Reflect the new total if the upgrade panel happens to be open.
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.dsh.maidmanager.network.MaidActionHandler.refresh(serverPlayer);
        }

        // Stop TLM opening her GUI and stop vanilla throwing the bottle we just consumed.
        event.setCanceled(true);
    }
}
