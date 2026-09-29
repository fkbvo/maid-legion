package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.logic.MaidManagerService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/**
 * Client -&gt; server: star or unstar a set of maids.
 *
 * <p>Favourites are purely a per-player convenience; they are validated the same way as
 * every other action, so a player can only star maids they actually own.
 */
public record C2SToggleFavouritePacket(List<UUID> targets, boolean favourite)
        implements CustomPacketPayload {

    public static final Type<C2SToggleFavouritePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "toggle_favourite"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SToggleFavouritePacket> CODEC =
            new StreamCodec<>() {
                @Override
                public C2SToggleFavouritePacket decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<UUID> targets = new java.util.ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        targets.add(buf.readUUID());
                    }
                    return new C2SToggleFavouritePacket(targets, buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SToggleFavouritePacket msg) {
                    buf.writeVarInt(msg.targets.size());
                    for (UUID id : msg.targets) {
                        buf.writeUUID(id);
                    }
                    buf.writeBoolean(msg.favourite);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SToggleFavouritePacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sender)) {
                return;
            }
            for (UUID id : msg.targets()) {
                MaidManagerService.setFavourite(sender, id, msg.favourite());
            }
            MaidActionHandler.refresh(sender);
        });
    }
}
