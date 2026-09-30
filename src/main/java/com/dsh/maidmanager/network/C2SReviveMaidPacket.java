package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.logic.MaidManagerService;
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
 * Client -&gt; server: revive one dead maid.
 *
 * <p>Only the maid id crosses the wire. Ownership, enrolment, the death record, the route and
 * whatever that route costs are all re-checked server-side, so a crafted packet can neither
 * revive someone else's maid nor skip the cost.
 */
public record C2SReviveMaidPacket(UUID maidId) implements CustomPacketPayload {

    public static final Type<C2SReviveMaidPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "revive_maid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SReviveMaidPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public C2SReviveMaidPacket decode(RegistryFriendlyByteBuf buf) {
                    return new C2SReviveMaidPacket(buf.readUUID());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SReviveMaidPacket msg) {
                    buf.writeUUID(msg.maidId);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SReviveMaidPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sender)) {
                return;
            }
            // Which route is used is decided here, from the player's bought ability, rather
            // than being named by the client: a crafted packet must not be able to pick the
            // cheaper route, and the two routes cost very different things.
            MaidManagerService.ReviveResult result = MaidProgressionService
                    .usesShrineRevive(sender)
                    ? MaidManagerService.beginShrineRevive(sender, msg.maidId)
                    : MaidManagerService.beginRevive(sender, msg.maidId);
            // Report refusals explicitly; a silent no-op looks like a broken button.
            switch (result) {
                case NEED_SHRINES -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.need_shrines",
                                MaidManagerService.SHRINE_REVIVE_COST), true);
                case ALREADY_CASTING -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.already_casting"), true);
                case STARTED_CASTING -> {
                    // beginShrineRevive already reported the cast length.
                }
                case NEED_MATERIALS -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.need_materials"), true);
                case NOT_DEAD -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.not_dead"), true);
                case NOT_OWNER -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.not_owner"), true);
                case NOT_ENROLLED -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.not_enrolled"), true);
                case FAILED -> sender.sendSystemMessage(
                        Component.translatable("message.touhou_maid_legion.revive_failed"));
                case STARTED -> {
                    // beginRevive already told the player she is back.
                }
            }
            MaidActionHandler.refresh(sender);
        });
    }
}
