package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -&gt; server: revive one dead maid, paying the altar's material list.
 *
 * <p>Only the maid id crosses the wire. Ownership, enrolment, the death record and the
 * materials are all re-checked server-side, so a crafted packet can neither revive someone
 * else's maid nor skip the cost.
 *
 * <p>1.20 note: Forge's {@code SimpleChannel} uses static encode/decode/handle methods and a
 * {@code NetworkEvent.Context}; the 1.21 branch uses a {@code CustomPacketPayload} record with
 * a {@code StreamCodec} instead.
 */
public class C2SReviveMaidPacket {
    private final UUID maidId;

    public C2SReviveMaidPacket(UUID maidId) {
        this.maidId = maidId;
    }

    public static void encode(C2SReviveMaidPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidId);
    }

    public static C2SReviveMaidPacket decode(FriendlyByteBuf buf) {
        return new C2SReviveMaidPacket(buf.readUUID());
    }

    public static void handle(C2SReviveMaidPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                MaidManagerService.ReviveResult result =
                        MaidManagerService.beginRevive(sender, msg.maidId);
                // Report refusals explicitly; a silent no-op looks like a broken button.
                switch (result) {
                    case NEED_MATERIALS -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.need_materials"), true);
                    case NOT_DEAD -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.not_dead"), true);
                    case NOT_OWNER -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.not_owner"), true);
                    case NOT_ENROLLED -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.not_enrolled"), true);
                    case FAILED -> sender.sendSystemMessage(
                            Component.translatable("message.maid_legion.revive_failed"));
                    case STARTED -> {
                        // beginRevive already told the player she is back.
                    }
                }
                C2SMaidActionPacket.refresh(sender);
            });
        }
        context.setPacketHandled(true);
    }
}