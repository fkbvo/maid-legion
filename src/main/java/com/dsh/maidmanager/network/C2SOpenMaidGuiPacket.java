package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidProgressionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -&gt; server: open a maid's own TLM GUI from the terminal, without walking to her.
 *
 * <p>TLM's {@code openMaidGui} performs no distance or ownership check of its own, so this packet
 * is the only gate. It is safe to expose because the server validates that the sender owns and
 * has enrolled the maid before calling into TLM.
 */
public class C2SOpenMaidGuiPacket {
    private final UUID maidId;

    public C2SOpenMaidGuiPacket(UUID maidId) {
        this.maidId = maidId;
    }

    public static void encode(C2SOpenMaidGuiPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidId);
    }

    public static C2SOpenMaidGuiPacket decode(FriendlyByteBuf buf) {
        return new C2SOpenMaidGuiPacket(buf.readUUID());
    }

    public static void handle(C2SOpenMaidGuiPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                MaidProgressionService.OpenResult result =
                        MaidProgressionService.openMaidGui(sender, msg.maidId);
                switch (result) {
                    case NOT_OWNER, NOT_ENROLLED -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.not_enrolled"), true);
                    case NOT_LOADED -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.open_gui_not_loaded"), true);
                    case WRONG_DIMENSION -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.open_gui_wrong_dim"), true);
                    case OK -> {
                        // Nothing to say - the container opening is the feedback.
                    }
                }
            });
        }
        context.setPacketHandled(true);
    }
}
