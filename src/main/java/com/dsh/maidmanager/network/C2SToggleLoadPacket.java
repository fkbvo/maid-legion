package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/**
 * Client -&gt; server: flip the per-maid "force load" switch.
 *
 * <p>{@code acknowledged} carries the player's acceptance of the heavy-load explanation so
 * the server can record it; the server decides whether that is required.
 */
public record C2SToggleLoadPacket(List<UUID> targets, boolean enabled, boolean acknowledged)
        implements CustomPacketPayload {

    public static final Type<C2SToggleLoadPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "toggle_load"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SToggleLoadPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public C2SToggleLoadPacket decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<UUID> targets = new java.util.ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        targets.add(buf.readUUID());
                    }
                    return new C2SToggleLoadPacket(targets, buf.readBoolean(), buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SToggleLoadPacket msg) {
                    buf.writeVarInt(msg.targets.size());
                    for (UUID id : msg.targets) {
                        buf.writeUUID(id);
                    }
                    buf.writeBoolean(msg.enabled);
                    buf.writeBoolean(msg.acknowledged);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SToggleLoadPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer sender)) {
                return;
            }
            if (!com.dsh.maidmanager.logic.MaidManagerService.forceLoadAllowed()) {
                // Server policy forbids it; ignore the request entirely.
                MaidActionHandler.refresh(sender);
                return;
            }
            if (msg.acknowledged()) {
                com.dsh.maidmanager.logic.MaidManagerService.acknowledge(sender);
            }
            for (UUID id : msg.targets()) {
                com.dsh.maidmanager.logic.MaidManagerService.setForceLoad(sender, id, msg.enabled());
            }
            MaidActionHandler.refresh(sender);
        });
    }
}
