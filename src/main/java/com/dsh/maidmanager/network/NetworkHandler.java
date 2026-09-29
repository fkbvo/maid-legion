package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Our own network channel.
 *
 * <p>1.21 / NeoForge replaced Forge's {@code SimpleChannel} with the vanilla
 * {@code CustomPacketPayload} system plus a {@link PayloadRegistrar}. Registration therefore
 * happens in {@link RegisterPayloadHandlersEvent} rather than in the constructor, and there
 * is no numeric message id to allocate - the payload {@code Type} (a {@code ResourceLocation})
 * <em>is</em> the id. Payloads are still registered one by one so the direction of each is
 * explicit.
 *
 * <p>Deliberately separate from TLM's channel so the two mods can version independently.
 */
public final class NetworkHandler {
    /** Bumped whenever a payload's wire format changes; NeoForge refuses mismatched peers. */
    private static final String VERSION = "1";

    private NetworkHandler() {
    }

    // NeoForge auto-selects the bus from the event type, so no `bus = ...` attribute is needed
    // (and the attribute itself is deprecated for removal).
    @EventBusSubscriber(modid = MaidManagerMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            PayloadRegistrar registrar = event.registrar(VERSION);

            registrar.playToClient(
                    S2CMaidListPacket.TYPE, S2CMaidListPacket.CODEC, S2CMaidListPacket::handle);
            registrar.playToServer(
                    C2SMaidActionPacket.TYPE, C2SMaidActionPacket.CODEC, C2SMaidActionPacket::handle);
            registrar.playToServer(
                    C2SToggleLoadPacket.TYPE, C2SToggleLoadPacket.CODEC, C2SToggleLoadPacket::handle);
            registrar.playToServer(
                    C2SToggleFavouritePacket.TYPE, C2SToggleFavouritePacket.CODEC,
                    C2SToggleFavouritePacket::handle);
            registrar.playToClient(
                    S2CActionResultPacket.TYPE, S2CActionResultPacket.CODEC,
                    S2CActionResultPacket::handle);
        }
    }

    /** Sends a payload to one player. Replaces Forge's {@code PacketDistributor.PLAYER}. */
    public static void sendToPlayer(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Sends a payload from the client to the server. Replaces {@code CHANNEL.sendToServer}. */
    public static void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        // NeoForge exposes the client->server direction on the same PacketDistributor class.
        // (There is no ClientHooks.sendToServer; ClientHooks only holds client-side hooks.)
        PacketDistributor.sendToServer(payload);
    }
}
