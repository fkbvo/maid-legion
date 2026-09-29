package com.dsh.maidmanager.network;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.logic.MaidManagerService;
import com.dsh.maidmanager.logic.MaidRegistry;
import com.dsh.maidmanager.util.MaidUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Server-side body of {@link C2SMaidActionPacket}.
 *
 * <p>Split out from the payload record so that the resolution logic stays identical in shape
 * to the 1.20 branch and does not have to live inside a codec class.
 */
public final class MaidActionHandler {

    private MaidActionHandler() {
    }

    public static void apply(ServerPlayer player, C2SMaidActionPacket msg) {
        int success = 0;
        int failed = 0;

        switch (msg.action()) {
            case REFRESH -> {
                // No mutation; just push a fresh snapshot back.
            }
            case SUMMON, STORE -> {
                int size = msg.targets().size();
                int budget = Math.min(size, Math.max(1, Config.COMMON.maxSummonPerAction.get()));
                for (int i = 0; i < budget; i++) {
                    boolean ok = msg.action() == C2SMaidActionPacket.Action.SUMMON
                            ? summonOne(player, msg.targets().get(i))
                            : MaidManagerService.store(player, msg.targets().get(i));
                    if (ok) {
                        success++;
                    } else {
                        failed++;
                    }
                }
                failed += Math.max(0, size - budget);
            }
        }

        NetworkHandler.sendToPlayer(player, new S2CActionResultPacket(success, failed, msg.action()));

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
        // Still unloaded. With the switch on, ask NeoForge to load her chunk and try again on a
        // later tick; report the outcome either way.
        boolean forceLoad = MaidRegistry.get(player.getServer()).isForceLoad(player.getUUID(), maidId);
        if (!forceLoad || !MaidUtil.isTlmAvailable()) {
            player.sendSystemMessage(Component.translatable("message.maid_legion.cannot_reach"));
            return false;
        }
        boolean started = MaidManagerService.beginForceLoadSummon(player, maidId);
        if (!started) {
            player.sendSystemMessage(Component.translatable("message.maid_legion.cannot_reach"));
        }
        return started;
    }

    public static void refresh(ServerPlayer player) {
        NetworkHandler.sendToPlayer(player, new S2CMaidListPacket(
                MaidManagerService.snapshot(player), MaidManagerService.forceLoadAllowed()));
    }
}
