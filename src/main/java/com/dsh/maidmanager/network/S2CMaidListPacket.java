package com.dsh.maidmanager.network;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.ProgressionInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -&gt; client: the full maid snapshot for the terminal, plus the player's progression. */
public class S2CMaidListPacket {
    private final List<MaidEntry> entries;
    private final boolean forceLoadAllowed;
    private final ProgressionInfo progression;

    public S2CMaidListPacket(List<MaidEntry> entries, boolean forceLoadAllowed) {
        this(entries, forceLoadAllowed, ProgressionInfo.empty());
    }

    public S2CMaidListPacket(List<MaidEntry> entries, boolean forceLoadAllowed,
                             ProgressionInfo progression) {
        this.entries = entries;
        this.forceLoadAllowed = forceLoadAllowed;
        this.progression = progression;
    }

    public static void encode(S2CMaidListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (MaidEntry entry : msg.entries) {
            entry.write(buf);
        }
        buf.writeBoolean(msg.forceLoadAllowed);
        msg.progression.write(buf);
    }

    public static S2CMaidListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<MaidEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(MaidEntry.read(buf));
        }
        boolean forceLoadAllowed = buf.readBoolean();
        return new S2CMaidListPacket(entries, forceLoadAllowed, ProgressionInfo.read(buf));
    }

    public static void handle(S2CMaidListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.dsh.maidmanager.client.ClientPayloadHandlers.onMaidList(
                            msg.entries, msg.forceLoadAllowed, msg.progression)));
        }
        context.setPacketHandled(true);
    }
}
