package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -&gt; server: revive one dead maid.
 *
 * <p>{@code useShrines} selects the payment: the altar's material list, or
 * {@link MaidManagerService#SHRINE_ALTERNATIVE_COUNT} shrines plus a waiting period.
 *
 * <p>Only the maid id and the payment choice cross the wire. Ownership, enrolment, the death
 * record and the materials are all re-checked server-side, so a crafted packet can neither
 * revive someone else's maid nor skip the cost.
 *
 * <p>1.20 note: Forge's {@code SimpleChannel} uses static encode/decode/handle methods and a
 * {@code NetworkEvent.Context}; the 1.21 branch uses a {@code CustomPacketPayload} record with
 * a {@code StreamCodec} instead.
 */
public class C2SReviveMaidPacket {
    private final UUID maidId;
    private final boolean useShrines;

    public C2SReviveMaidPacket(UUID maidId, boolean useShrines) {
        this.maidId = maidId;
        this.useShrines = useShrines;
    }

    public static void encode(C2SReviveMaidPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidId);
        buf.writeBoolean(msg.useShrines);
    }

    public static C2SReviveMaidPacket decode(FriendlyByteBuf buf) {
        return new C2SReviveMaidPacket(buf.readUUID(), buf.readBoolean());
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
                        MaidManagerService.beginRevive(sender, msg.maidId, msg.useShrines);
                // Report refusals explicitly; a silent no-op looks like a broken button.
                switch (result) {
                    case NEED_MATERIALS -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.need_materials"), true);
                    case NEED_SHRINES -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.need_shrines",
                                    MaidManagerService.SHRINE_ALTERNATIVE_COUNT), true);
                    case ALREADY_PENDING -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.revive_pending"), true);
                    case NOT_DEAD -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.not_dead"), true);
                    case NOT_OWNED -> sender.displayClientMessage(
                            Component.translatable("message.maid_legion.not_enrolled"), true);
                    case STARTED -> {
                        // beginRevive already sent the "started" message with the delay.
                    }
                }
                C2SMaidActionPacket.refresh(sender);
            });
        }
        context.setPacketHandled(true);
    }
}