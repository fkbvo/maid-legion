package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Our own network channel. Deliberately separate from TLM's channel so the two mods can
 * version independently and so we never occupy or collide with a TLM message id.
 */
public final class NetworkHandler {
    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MaidManagerMod.MOD_ID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private NetworkHandler() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, S2CMaidListPacket.class,
                S2CMaidListPacket::encode, S2CMaidListPacket::decode, S2CMaidListPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, C2SMaidActionPacket.class,
                C2SMaidActionPacket::encode, C2SMaidActionPacket::decode, C2SMaidActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, C2SToggleLoadPacket.class,
                C2SToggleLoadPacket::encode, C2SToggleLoadPacket::decode, C2SToggleLoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, C2SToggleFavouritePacket.class,
                C2SToggleFavouritePacket::encode, C2SToggleFavouritePacket::decode,
                C2SToggleFavouritePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, C2SReviveMaidPacket.class,
                C2SReviveMaidPacket::encode, C2SReviveMaidPacket::decode, C2SReviveMaidPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, S2CActionResultPacket.class,
                S2CActionResultPacket::encode, S2CActionResultPacket::decode, S2CActionResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
