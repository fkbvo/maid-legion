package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidProgressionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -&gt; server: buy one upgrade.
 *
 * <p>A null {@code maidId} means a legion-wide ability; anything else is a per-maid upgrade. Both
 * share a packet because they differ only in which wallet the server charges, and the server
 * re-reads the definition from the id either way - the client cannot influence the price.
 */
public class C2SUpgradePacket {
    private final UUID maidId;
    private final String upgradeId;

    public C2SUpgradePacket(UUID maidId, String upgradeId) {
        this.maidId = maidId;
        this.upgradeId = upgradeId;
    }

    public static void encode(C2SUpgradePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.maidId != null);
        if (msg.maidId != null) {
            buf.writeUUID(msg.maidId);
        }
        buf.writeUtf(msg.upgradeId);
    }

    public static C2SUpgradePacket decode(FriendlyByteBuf buf) {
        UUID maidId = buf.readBoolean() ? buf.readUUID() : null;
        return new C2SUpgradePacket(maidId, buf.readUtf());
    }

    public static void handle(C2SUpgradePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                MaidProgressionService.UpgradeResult result = msg.maidId == null
                        ? MaidProgressionService.buyGlobalUpgrade(sender, msg.upgradeId)
                        : MaidProgressionService.buyMaidUpgrade(sender, msg.maidId, msg.upgradeId);
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
                C2SMaidActionPacket.refresh(sender);
            });
        }
        context.setPacketHandled(true);
    }
}
