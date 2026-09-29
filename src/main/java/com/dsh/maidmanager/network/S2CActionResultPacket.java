package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -&gt; client: how many maids were handled, so the GUI can report it. */
public record S2CActionResultPacket(int success, int failed, C2SMaidActionPacket.Action action)
        implements CustomPacketPayload {

    public static final Type<S2CActionResultPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "action_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CActionResultPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public S2CActionResultPacket decode(RegistryFriendlyByteBuf buf) {
                    return new S2CActionResultPacket(buf.readVarInt(), buf.readVarInt(),
                            buf.readEnum(C2SMaidActionPacket.Action.class));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, S2CActionResultPacket msg) {
                    buf.writeVarInt(msg.success);
                    buf.writeVarInt(msg.failed);
                    buf.writeEnum(msg.action);
                }
            };

    public Component describe() {
        String key = "message.maid_legion.result." + action.name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable(key, success, failed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CActionResultPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> com.dsh.maidmanager.client.ClientPayloadHandlers.onActionResult(msg));
    }
}
