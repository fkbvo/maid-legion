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
 * Client -&gt; server: star or unstar a set of maids.
 *
 * <p>Favourites are purely a per-player convenience; they are validated the same way as
 * every other action, so a player can only star maids they actually own.
 */
public class C2SToggleFavouritePacket {
    private final List<UUID> targets;
    private final boolean favourite;

    public C2SToggleFavouritePacket(List<UUID> targets, boolean favourite) {
        this.targets = targets;
        this.favourite = favourite;
    }

    public static void encode(C2SToggleFavouritePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.targets.size());
        for (UUID id : msg.targets) {
            buf.writeUUID(id);
        }
        buf.writeBoolean(msg.favourite);
    }

    public static C2SToggleFavouritePacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<UUID> targets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            targets.add(buf.readUUID());
        }
        return new C2SToggleFavouritePacket(targets, buf.readBoolean());
    }

    public static void handle(C2SToggleFavouritePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                for (UUID id : msg.targets) {
                    MaidManagerService.setFavourite(sender, id, msg.favourite);
                }
                C2SMaidActionPacket.refresh(sender);
            });
        }
        context.setPacketHandled(true);
    }
}
