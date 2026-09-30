package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.GlobalUpgrade;
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
    /** Buying a level, or flipping a toggleable ability's switch. */
    public enum Mode {
        BUY,
        TOGGLE
    }

    private final UUID maidId;
    private final String upgradeId;
    private final Mode mode;

    public C2SUpgradePacket(UUID maidId, String upgradeId) {
        this(maidId, upgradeId, Mode.BUY);
    }

    public C2SUpgradePacket(UUID maidId, String upgradeId, Mode mode) {
        this.maidId = maidId;
        this.upgradeId = upgradeId;
        this.mode = mode;
    }

    public static void encode(C2SUpgradePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.maidId != null);
        if (msg.maidId != null) {
            buf.writeUUID(msg.maidId);
        }
        buf.writeUtf(msg.upgradeId);
        buf.writeEnum(msg.mode);
    }

    public static C2SUpgradePacket decode(FriendlyByteBuf buf) {
        UUID maidId = buf.readBoolean() ? buf.readUUID() : null;
        String upgradeId = buf.readUtf();
        return new C2SUpgradePacket(maidId, upgradeId, buf.readEnum(Mode.class));
    }

    public static void handle(C2SUpgradePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                if (msg.mode == Mode.TOGGLE) {
                    GlobalUpgrade ability = GlobalUpgrade.byId(msg.upgradeId);
                    if (ability != null) {
                        boolean nowOn = MaidProgressionService.toggleAbility(sender, ability);
                        sender.displayClientMessage(Component.translatable(nowOn
                                ? "message.touhou_maid_legion.ability_on"
                                : "message.touhou_maid_legion.ability_off"), true);
                    }
                    C2SMaidActionPacket.refresh(sender);
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
