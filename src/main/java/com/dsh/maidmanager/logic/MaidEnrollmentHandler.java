package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTamedEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Enrols maids into the panel when the player shift-right-clicks them with a gohei.
 *
 * <p>Panel management is deliberately <em>opt-in</em>. The terminal can summon, store and
 * revive maids, so auto-including every maid the player owns would mean a stray click could
 * yank a maid the player never intended to command from a GUI. Requiring an explicit
 * gesture makes the roster exactly what the player chose.
 *
 * <p>Both gohei count. TLM registers two items - {@code hakurei_gohei} and
 * {@code sanae_gohei} - and both are instances of the same {@link ItemHakureiGohei} class,
 * which is why the check is {@code instanceof} rather than an item identity comparison. That
 * also means any addon gohei extending the same class works for free.
 *
 * <p>Hooking: TLM posts {@link InteractMaidEvent} from {@code EntityMaid.mobInteract} before
 * it opens the maid GUI, and that event is cancellable. We consume it only for the
 * shift-click gesture so normal right-click behaviour is left completely untouched.
 */
@EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class MaidEnrollmentHandler {

    private MaidEnrollmentHandler() {
    }

    /**
     * Shift-right-click a maid with a gohei to add or remove her from the panel.
     *
     * <p>Cancelling the event stops TLM from also opening the maid's own GUI, so the gesture
     * reads as a single deliberate action rather than doing two things at once.
     */
    @SubscribeEvent
    public static void onInteractMaid(InteractMaidEvent event) {
        Player player = event.getPlayer();
        // Guard the side first: the event fires on both, but enrolment is server state.
        if (player.level().isClientSide()) {
            return;
        }
        if (!player.isShiftKeyDown()) {
            return;
        }
        if (!isGohei(event.getStack())) {
            return;
        }
        EntityMaid maid = event.getMaid();
        if (!maid.isOwnedBy(player)) {
            // Not our maid: say so rather than silently doing nothing, otherwise the gesture
            // looks broken.
            player.displayClientMessage(
                    Component.translatable("message.touhou_maid_legion.not_owner"), true);
            event.setCanceled(true);
            return;
        }

        MaidRegistry registry = MaidRegistry.get(player.getServer());
        boolean nowEnrolled = !registry.isEnrolled(player.getUUID(), maid.getUUID());
        registry.setEnrolled(player.getUUID(), maid.getUUID(), nowEnrolled);

        player.displayClientMessage(Component.translatable(nowEnrolled
                ? "message.touhou_maid_legion.enrolled"
                : "message.touhou_maid_legion.removed", maid.getName()), true);

        // Reflect it immediately if the panel happens to be open.
        if (player instanceof ServerPlayer serverPlayer) {
            com.dsh.maidmanager.network.MaidActionHandler.refresh(serverPlayer);
        }

        event.setCanceled(true);
    }

    /**
     * Images of the gohei held in either hand should work, but {@link InteractMaidEvent}'s
     * stack is the main hand. The off-hand case is handled by
     * {@link #onInteractMaid}.
     */
    private static boolean isGohei(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemHakureiGohei;
    }

    /**
     * A newly tamed maid is enrolled automatically.
     *
     * <p>Taming is already an explicit, deliberate act aimed at that specific maid, so asking
     * for a second gesture immediately afterwards would be busywork. Maids that were already
     * owned before this mod was installed still need the gohei gesture.
     */
    @SubscribeEvent
    public static void onMaidTamed(MaidTamedEvent event) {
        if (event.getPlayer().level().isClientSide()) {
            return;
        }
        Player player = event.getPlayer();
        EntityMaid maid = event.getMaid();
        MaidRegistry.get(player.getServer()).setEnrolled(player.getUUID(), maid.getUUID(), true);
    }

    /**
     * Off-hand gohei support.
     *
     * <p>{@code InteractMaidEvent} is only posted for the main hand, so a gohei held in the
     * off hand never reaches {@link #onInteractMaid} - the vanilla interaction would instead
     * fall through to TLM's taming path and waste the gohei. Catching the entity interaction
     * here lets the off hand behave the same as the main hand.
     */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        Player player = event.getEntity();
        if (player.level().isClientSide() || !player.isShiftKeyDown()) {
            return;
        }
        if (!isGohei(player.getOffhandItem())) {
            return;
        }
        // The main-hand path already handled this gesture; don't double-toggle.
        if (isGohei(player.getMainHandItem())) {
            return;
        }
        if (!maid.isOwnedBy(player)) {
            return;
        }

        MaidRegistry registry = MaidRegistry.get(player.getServer());
        boolean nowEnrolled = !registry.isEnrolled(player.getUUID(), maid.getUUID());
        registry.setEnrolled(player.getUUID(), maid.getUUID(), nowEnrolled);
        player.displayClientMessage(Component.translatable(nowEnrolled
                ? "message.touhou_maid_legion.enrolled"
                : "message.touhou_maid_legion.removed", maid.getName()), true);

        if (player instanceof ServerPlayer serverPlayer) {
            com.dsh.maidmanager.network.MaidActionHandler.refresh(serverPlayer);
        }
        event.setCanceled(true);
    }
}
