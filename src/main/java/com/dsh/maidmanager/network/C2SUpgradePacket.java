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
 * Client -&gt; server: buy one upgrade.
 *
 * <p>A null {@code maidId} means a legion-wide ability; anything else is a per-maid upgrade. Both
 * share a packet because they differ only in which wallet the server charges, and the server
 * re-reads the definition from the id either way - the client cannot influence the price.
 */
public record C2SUpgradePacket(UUID maidId, String upgradeId) implements CustomPacketPayload {

    public static final Type<C2SUpgradePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "upgrade"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SUpgradePacket> CODEC =
            new StreamCodec<>() {
                @Override
                public C2SUpgradePacket decode(RegistryFriendlyByteBuf buf) {
                    UUID maidId = buf.readBoolean() ? buf.readUUID() : null;
                    return new C2SUpgradePacket(maidId, buf.readUtf());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SUpgradePacket msg) {
                    buf.writeBoolean(msg.maidId != null);
                    if (msg.maidId != null) {
                        buf.writeUUID(msg.maidId);
                    }
                    buf.writeUtf(msg.upgradeId);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SUpgradePacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sender)) {
                return;
            }
            MaidProgressionService.UpgradeResult result = msg.maidId() == null
                    ? MaidProgressionService.buyGlobalUpgrade(sender, msg.upgradeId())
                    : MaidProgressionService.buyMaidUpgrade(sender, msg.maidId(), msg.upgradeId());
            // Every refusal says what went wrong; a silent no-op reads as a broken button.
            switch (result) {
                case NOT_ENOUGH_EXP -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.upgrade_no_exp"), true);
                case NOT_ENOUGH_POWER -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.upgrade_no_power"), true);
                case MAX_LEVEL -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.upgrade_maxed"), true);
                case UNREACHABLE -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.upgrade_unreachable"), true);
                case DISABLED -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.upgrade_disabled"), true);
                case UNKNOWN_UPGRADE -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.upgrade_unknown"), true);
                case NOT_OWNER, NOT_ENROLLED -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.not_enrolled"), true);
                case OK -> sender.displayClientMessage(
                        Component.translatable("message.touhou_maid_legion.upgrade_ok"), true);
            }
            MaidActionHandler.refresh(sender);
        });
    }
}