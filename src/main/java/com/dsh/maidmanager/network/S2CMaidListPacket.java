package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -&gt; client: the full maid snapshot for the terminal. */
public class S2CMaidListPacket {
    private final List<MaidEntry> entries;
    private final boolean forceLoadAllowed;

    public S2CMaidListPacket(List<MaidEntry> entries, boolean forceLoadAllowed) {
        this.entries = entries;
        this.forceLoadAllowed = forceLoadAllowed;
    }

    public static void encode(S2CMaidListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (MaidEntry entry : msg.entries) {
            entry.write(buf);
        }
        buf.writeBoolean(msg.forceLoadAllowed);
    }

    public static S2CMaidListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<MaidEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(MaidEntry.read(buf));
        }
        return new S2CMaidListPacket(entries, buf.readBoolean());
    }

    public static void handle(S2CMaidListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.dsh.maidmanager.client.ClientPayloadHandlers.onMaidList(msg.entries, msg.forceLoadAllowed)));
        }
        context.setPacketHandled(true);
    }
}
