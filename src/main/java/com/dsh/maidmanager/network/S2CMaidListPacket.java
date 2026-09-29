package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.ProgressionInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Server -&gt; client: the full maid snapshot for the terminal, plus the player's progression. */
public record S2CMaidListPacket(List<MaidEntry> entries, boolean forceLoadAllowed,
                                ProgressionInfo progression) implements CustomPacketPayload {

    public static final Type<S2CMaidListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "maid_list"));

    public S2CMaidListPacket(List<MaidEntry> entries, boolean forceLoadAllowed) {
        this(entries, forceLoadAllowed, ProgressionInfo.empty());
    }

    /**
     * {@code MaidEntry} is a hand-written value object rather than a codec-annotated record,
     * so the list is encoded through its existing {@code write}/{@code read} pair instead of
     * deriving a codec.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidListPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public S2CMaidListPacket decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<MaidEntry> entries = new java.util.ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        entries.add(MaidEntry.read(buf));
                    }
                    return new S2CMaidListPacket(entries, buf.readBoolean(),
                            ProgressionInfo.read(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, S2CMaidListPacket msg) {
                    buf.writeVarInt(msg.entries.size());
                    for (MaidEntry entry : msg.entries) {
                        entry.write(buf);
                    }
                    buf.writeBoolean(msg.forceLoadAllowed);
                    msg.progression.write(buf);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CMaidListPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> com.dsh.maidmanager.client.ClientPayloadHandlers
                .onMaidList(msg.entries(), msg.forceLoadAllowed(), msg.progression()));
    }
}