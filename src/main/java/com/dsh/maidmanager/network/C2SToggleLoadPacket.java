package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -&gt; server: flip the per-maid "force load" switch.
 *
 * <p>{@code acknowledged} carries the player's acceptance of the heavy-load explanation so
 * the server can record it; the server decides whether that is required.
 */
public class C2SToggleLoadPacket {
    private final List<UUID> targets;
    private final boolean enabled;
    private final boolean acknowledged;

    public C2SToggleLoadPacket(List<UUID> targets, boolean enabled, boolean acknowledged) {
        this.targets = targets;
        this.enabled = enabled;
        this.acknowledged = acknowledged;
    }

    public static void encode(C2SToggleLoadPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.targets.size());
        for (UUID id : msg.targets) {
            buf.writeUUID(id);
        }
        buf.writeBoolean(msg.enabled);
        buf.writeBoolean(msg.acknowledged);
    }

    public static C2SToggleLoadPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<UUID> targets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            targets.add(buf.readUUID());
        }
        return new C2SToggleLoadPacket(targets, buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(C2SToggleLoadPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                if (!MaidManagerService.forceLoadAllowed()) {
                    // Server policy forbids it; ignore the request entirely.
                    C2SMaidActionPacket.refresh(sender);
                    return;
                }
                if (msg.acknowledged) {
                    MaidManagerService.acknowledge(sender);
                }
                for (UUID id : msg.targets) {
                    MaidManagerService.setForceLoad(sender, id, msg.enabled);
                }
                C2SMaidActionPacket.refresh(sender);
            });
        }
        context.setPacketHandled(true);
    }
}
