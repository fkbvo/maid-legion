package com.dsh.maidmanager.network;

import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.logic.MaidStorage;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.logic.MaidManagerService;
import com.dsh.maidmanager.logic.MaidRegistry;
import com.dsh.maidmanager.util.MaidUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -&gt; server: request an action on a set of maids.
 *
 * <p>The UUIDs here are treated purely as an *intent*. The server looks up each maid and
 * re-checks ownership before acting, so a crafted packet cannot affect another player.
 */
public class C2SMaidActionPacket {
    public enum Action {
        REFRESH,
        SUMMON,
        STORE
    }

    private final Action action;
    private final List<UUID> targets;

    public C2SMaidActionPacket(Action action, List<UUID> targets) {
        this.action = action;
        this.targets = targets;
    }

    public static void encode(C2SMaidActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeVarInt(msg.targets.size());
        for (UUID id : msg.targets) {
            buf.writeUUID(id);
        }
    }

    public static C2SMaidActionPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        int size = buf.readVarInt();
        List<UUID> targets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            targets.add(buf.readUUID());
        }
        return new C2SMaidActionPacket(action, targets);
    }

    public static void handle(C2SMaidActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                apply(sender, msg);
            });
        }
        context.setPacketHandled(true);
    }

    private static void apply(ServerPlayer player, C2SMaidActionPacket msg) {
        int success = 0;
        int failed = 0;

        switch (msg.action) {
            case REFRESH -> {
                // No mutation; just push a fresh snapshot back.
            }
            case SUMMON -> {
                int budget = Math.min(msg.targets.size(), Math.max(1, Config.COMMON.maxSummonPerAction.get()));
                // Checked once for the whole batch, and sent to chat rather than the action bar.
                // Two reasons: the problem is about the player and not about any one maid, so
                // repeating it per maid would spam; and the action bar is where the result summary
                // goes, which is sent just afterwards and would overwrite this completely.
                if (MaidManagerService.isAirborne(player) && anyStored(player, msg.targets, budget)) {
                    player.displayClientMessage(
                            Component.translatable("message.touhou_maid_legion.land_first"), false);
                    failed += budget;
                } else {
                    for (int i = 0; i < budget; i++) {
                        if (summonOne(player, msg.targets.get(i))) {
                            success++;
                        } else {
                            failed++;
                        }
                    }
                }
                failed += Math.max(0, msg.targets.size() - budget);
            }
            case STORE -> {
                int budget = Math.min(msg.targets.size(), Math.max(1, Config.COMMON.maxSummonPerAction.get()));
                for (int i = 0; i < budget; i++) {
                    if (MaidManagerService.store(player, msg.targets.get(i))) {
                        success++;
                    } else {
                        failed++;
                    }
                }
                failed += Math.max(0, msg.targets.size() - budget);
            }
        }

        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2CActionResultPacket(success, failed, msg.action));

        // Always follow up with a fresh list so the GUI reflects reality.
        refresh(player);
    }

    /**
     * Summons one maid, choosing stored / loaded handling automatically.
     *
     * <p>Order matters:
     * <ol>
     *   <li>a maid we stored ourselves is released from NBT - no chunk loading needed;</li>
     *   <li>a maid that is loaded anywhere is teleported (cross-dimension handled inside);</li>
     *   <li>a maid TLM knows about but which is not loaded is only reachable if her chunk is
     *       force-loaded, which is what the per-maid switch enables.</li>
     * </ol>
     */
    private static boolean summonOne(ServerPlayer player, UUID maidId) {

        // Stored by us: release from NBT, no chunk loading needed.
        if (MaidManagerService.releaseStored(player, maidId)) {
            return true;
        }
        // Already loaded somewhere: teleport (cross-dimension handled inside).
        var loaded = MaidManagerService.findLoadedMaid(player, maidId);
        if (loaded != null) {
            return MaidManagerService.summonLoaded(player, loaded);
        }
        // Still unloaded. With the switch on, ask Forge to load her chunk and try again on a
        // later tick; report the outcome either way.
        boolean forceLoad = MaidRegistry.get(player.getServer()).isForceLoad(player.getUUID(), maidId);
        if (!forceLoad || !MaidUtil.isTlmAvailable()) {
            // Logged so this path is distinguishable from a storage failure: the message shown to
            // the player is the same either way, and they are very different bugs.
            MaidManagerMod.LOGGER.warn(
                    "Summon {} refused: not stored, not loaded, forceLoad={} tlmAvailable={}",
                    maidId, forceLoad, MaidUtil.isTlmAvailable());
            player.sendSystemMessage(Component.translatable("message.touhou_maid_legion.cannot_reach"));
            return false;
        }
        boolean started = MaidManagerService.beginForceLoadSummon(player, maidId);
        if (!started) {
            player.sendSystemMessage(Component.translatable("message.touhou_maid_legion.cannot_reach"));
        }
        return started;
    }

    /**
     * Whether any maid in this action is one we hold as data, and so would have to be placed.
     *
     * <p>Only those need ground: a maid already in the world is teleported, not released.
     */
    private static boolean anyStored(ServerPlayer player, java.util.List<UUID> targets, int budget) {
        MaidStorage storage = MaidStorage.get(player.getServer());
        for (int i = 0; i < budget; i++) {
            if (storage.contains(player.getUUID(), targets.get(i))) {
                return true;
            }
        }
        return false;
    }
    public static void refresh(ServerPlayer player) {
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2CMaidListPacket(MaidManagerService.snapshot(player),
                        MaidManagerService.forceLoadAllowed(),
                        MaidManagerService.progression(player)));
    }
}
