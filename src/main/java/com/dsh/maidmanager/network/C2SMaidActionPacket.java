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
 * Client -&gt; server: request an action on a set of maids.
 *
 * <p>The UUIDs here are treated purely as an <em>intent</em>. The server looks up each maid
 * and re-checks ownership before acting, so a crafted packet cannot affect another player.
 */
public record C2SMaidActionPacket(Action action, List<UUID> targets) implements CustomPacketPayload {

    public enum Action {
        REFRESH,
        SUMMON,
        STORE
    }

    public static final Type<C2SMaidActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "maid_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SMaidActionPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public C2SMaidActionPacket decode(RegistryFriendlyByteBuf buf) {
                    Action action = buf.readEnum(Action.class);
                    int size = buf.readVarInt();
                    List<UUID> targets = new java.util.ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        targets.add(buf.readUUID());
                    }
                    return new C2SMaidActionPacket(action, targets);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SMaidActionPacket msg) {
                    buf.writeEnum(msg.action);
                    buf.writeVarInt(msg.targets.size());
                    for (UUID id : msg.targets) {
                        buf.writeUUID(id);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SMaidActionPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer sender) {
                MaidActionHandler.apply(sender, msg);
            }
        });
    }
}
