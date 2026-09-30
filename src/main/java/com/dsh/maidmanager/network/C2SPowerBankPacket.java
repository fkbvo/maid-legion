package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.logic.MaidProgressionService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -&gt; server: move P-points between TLM's wallet and the Legion bank, or toggle the
 * automatic sweep.
 *
 * <p>No amount travels with the request. "All" is evaluated server-side against the live wallet
 * and the live bank balance, so a stale screen cannot ask to deposit more than exists.
 */
public record C2SPowerBankPacket(Action action) implements CustomPacketPayload {

    public enum Action {
        /** Wallet -&gt; bank. */
        DEPOSIT,
        /** Bank -&gt; wallet, bounded by the wallet's headroom. */
        WITHDRAW,
        /** Flip the per-player automatic sweep. */
        TOGGLE_AUTO
    }

    public static final Type<C2SPowerBankPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "power_bank"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SPowerBankPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public C2SPowerBankPacket decode(RegistryFriendlyByteBuf buf) {
                    return new C2SPowerBankPacket(buf.readEnum(Action.class));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SPowerBankPacket msg) {
                    buf.writeEnum(msg.action);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SPowerBankPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sender)) {
                return;
            }
            switch (msg.action()) {
                case DEPOSIT -> {
                    float moved = MaidProgressionService.depositAll(sender);
                    sender.displayClientMessage(moved > 0.0F
                            ? Component.translatable("message.touhou_maid_legion.bank_deposit",
                            String.format("%.2f", moved))
                            : Component.translatable("message.touhou_maid_legion.bank_nothing"), true);
                }
                case WITHDRAW -> {
                    float taken = MaidProgressionService.withdrawAll(sender);
                    sender.displayClientMessage(taken > 0.0F
                            ? Component.translatable("message.touhou_maid_legion.bank_withdraw",
                            String.format("%.2f", taken))
                            : Component.translatable("message.touhou_maid_legion.bank_wallet_full"), true);
                }
                case TOGGLE_AUTO -> {
                    boolean enabled = !MaidProgressionService.autoDepositEnabled(sender);
                    MaidProgressionService.setAutoDeposit(sender, enabled);
                    sender.displayClientMessage(Component.translatable(enabled
                            ? "message.touhou_maid_legion.bank_auto_on"
                            : "message.touhou_maid_legion.bank_auto_off"), true);
                }
            }
            MaidActionHandler.refresh(sender);
        });
    }
}