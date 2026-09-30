package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Binds a shrine lamp to a player: sneak-right-click one with a gohei.
 *
 * <p>TLM's own right-click on a lamp opens its GUI, so the sneak modifier is what separates the
 * two gestures and the event is cancelled to stop both happening at once. Sneaking already means
 * "this click is for the other mod" throughout this mod - the gohei's enrolment gesture uses the
 * same convention - so the binding reads as part of the same family of actions.
 *
 * <p>The binding is only a remembered position. Nothing is consumed, and a lamp can be rebound at
 * any time; clicking the bound lamp again with the same gesture clears it.
 */
@Mod.EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
public final class LampBindHandler {

    private LampBindHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Server decides the binding; the client would only produce a duplicate message.
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        if (!player.isShiftKeyDown()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemHakureiGohei)) {
            return;
        }
        BlockPos pos = event.getPos();
        Level level = event.getLevel();
        if (!level.getBlockState(pos).is(InitBlocks.MAID_BEACON.get())) {
            return;
        }

        boolean bound = MaidProgressionService.bindLamp(player, pos);
        player.displayClientMessage(Component.translatable(bound
                ? "message.touhou_maid_legion.lamp_bound"
                : "message.touhou_maid_legion.lamp_unbound"), true);

        // Stop TLM opening the lamp GUI on the same click.
        event.setCanceled(true);
    }
}
