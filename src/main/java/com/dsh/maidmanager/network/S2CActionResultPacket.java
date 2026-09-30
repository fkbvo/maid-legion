package com.dsh.maidmanager.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -&gt; client: how many maids were handled, so the GUI can report it. */
public class S2CActionResultPacket {
    private final int success;
    private final int failed;
    private final C2SMaidActionPacket.Action action;

    public S2CActionResultPacket(int success, int failed, C2SMaidActionPacket.Action action) {
        this.success = success;
        this.failed = failed;
        this.action = action;
    }

    public Component describe() {
        String key = "message.touhou_maid_legion.result." + action.name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable(key, success, failed);
    }

    public static void encode(S2CActionResultPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.success);
        buf.writeVarInt(msg.failed);
        buf.writeEnum(msg.action);
    }

    public static S2CActionResultPacket decode(FriendlyByteBuf buf) {
        return new S2CActionResultPacket(
                buf.readVarInt(), buf.readVarInt(), buf.readEnum(C2SMaidActionPacket.Action.class));
    }

    public static void handle(S2CActionResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.dsh.maidmanager.client.ClientPayloadHandlers.onActionResult(msg)));
        }
        context.setPacketHandled(true);
    }
}
