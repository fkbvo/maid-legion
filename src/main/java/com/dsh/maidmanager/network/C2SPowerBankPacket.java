package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidProgressionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -&gt; server: move P-points between TLM's wallet and the Legion bank, or toggle the
 * automatic sweep.
 *
 * <p>No amount travels with the request. "All" is evaluated server-side against the live wallet
 * and the live bank balance, so a stale screen cannot ask to deposit more than exists.
 */
public class C2SPowerBankPacket {

    public enum Action {
        /** Wallet -&gt; bank. */
        DEPOSIT,
        /** Bank -&gt; wallet, bounded by the wallet's headroom. */
        WITHDRAW,
        /** Flip the per-player automatic sweep. */
        TOGGLE_AUTO
    }

    private final Action action;

    public C2SPowerBankPacket(Action action) {
        this.action = action;
    }

    public static void encode(C2SPowerBankPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
    }

    public static C2SPowerBankPacket decode(FriendlyByteBuf buf) {
        return new C2SPowerBankPacket(buf.readEnum(Action.class));
    }

    public static void handle(C2SPowerBankPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                switch (msg.action) {
                    case DEPOSIT -> {
                        float moved = MaidProgressionService.depositAll(sender);
                        sender.displayClientMessage(moved > 0.0F
                                ? Component.translatable("message.maid_legion.bank_deposit",
                                String.format("%.2f", moved))
                                : Component.translatable("message.maid_legion.bank_nothing"), true);
                    }
                    case WITHDRAW -> {
                        float taken = MaidProgressionService.withdrawAll(sender);
                        sender.displayClientMessage(taken > 0.0F
                                ? Component.translatable("message.maid_legion.bank_withdraw",
                                String.format("%.2f", taken))
                                : Component.translatable("message.maid_legion.bank_wallet_full"), true);
                    }
                    case TOGGLE_AUTO -> {
                        boolean enabled = !MaidProgressionService.autoDepositEnabled(sender);
                        MaidProgressionService.setAutoDeposit(sender, enabled);
                        sender.displayClientMessage(Component.translatable(enabled
                                ? "message.maid_legion.bank_auto_on"
                                : "message.maid_legion.bank_auto_off"), true);
                    }
                }
                C2SMaidActionPacket.refresh(sender);
            });
        }
        context.setPacketHandled(true);
    }
}
