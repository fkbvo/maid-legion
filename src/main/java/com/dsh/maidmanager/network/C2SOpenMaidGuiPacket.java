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

import java.util.UUID;

/**
 * Client -&gt; server: open a maid's own TLM GUI from the terminal, without walking to her.
 *
 * <p>TLM's {@code openMaidGui} performs no distance or ownership check of its own, so this packet
 * is the only gate. It is safe to expose because the server validates that the sender owns and
 * has enrolled the maid before calling into TLM.
 */
public record C2SOpenMaidGuiPacket(UUID maidId) implements CustomPacketPayload {

    public static final Type<C2SOpenMaidGuiPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "open_maid_gui"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SOpenMaidGuiPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public C2SOpenMaidGuiPacket decode(RegistryFriendlyByteBuf buf) {
                    return new C2SOpenMaidGuiPacket(buf.readUUID());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SOpenMaidGuiPacket msg) {
                    buf.writeUUID(msg.maidId);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SOpenMaidGuiPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sender)) {
                return;
            }
            MaidProgressionService.OpenResult result =
                    MaidProgressionService.openMaidGui(sender, msg.maidId());
            switch (result) {
                case NOT_OWNER, NOT_ENROLLED -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.not_enrolled"), true);
                case NOT_LOADED -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.open_gui_not_loaded"), true);
                case WRONG_DIMENSION -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.open_gui_wrong_dim"), true);
                case OK -> {
                    // Nothing to say - the container opening is the feedback.
                }
            }
        });
    }
}