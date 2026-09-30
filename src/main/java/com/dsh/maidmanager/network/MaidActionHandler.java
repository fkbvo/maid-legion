package com.dsh.maidmanager.network;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.logic.MaidManagerService;
import com.dsh.maidmanager.logic.MaidRegistry;
import com.dsh.maidmanager.logic.MaidStorage;
import com.dsh.maidmanager.util.MaidUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
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
                if (msg.action() == C2SMaidActionPacket.Action.SUMMON
                        && MaidManagerService.isAirborne(player)
                        && anyStored(player, msg.targets(), budget)) {
                    // Checked once for the whole batch, and sent to chat rather than the action bar.
                    // Two reasons: the problem is about the player and not about any one maid, so
                    // repeating it per maid would spam; and the action bar is where the result
                    // summary goes, which is sent just afterwards and would overwrite this
                    // completely.
                    player.displayClientMessage(
                            Component.translatable("message.touhou_maid_legion.land_first"), false);
                    failed += budget;
                } else {
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
    private static boolean anyStored(ServerPlayer player, List<UUID> targets, int budget) {
        MaidStorage storage = MaidStorage.get(player.getServer());
        for (int i = 0; i < budget; i++) {
            if (storage.contains(player.getUUID(), targets.get(i))) {
                return true;
            }
        }
        return false;
    }

    public static void refresh(ServerPlayer player) {
        NetworkHandler.sendToPlayer(player, new S2CMaidListPacket(
                MaidManagerService.snapshot(player), MaidManagerService.forceLoadAllowed(),
                MaidManagerService.progression(player)));
    }
}
