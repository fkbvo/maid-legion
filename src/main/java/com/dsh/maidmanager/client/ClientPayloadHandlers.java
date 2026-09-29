package com.dsh.maidmanager.client;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.ProgressionInfo;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Handles server packets on the client. */
public final class ClientPayloadHandlers {
    private static volatile boolean forceLoadAllowed = true;
    private static volatile ProgressionInfo progression = ProgressionInfo.empty();

    private ClientPayloadHandlers() {
    }

    public static void onMaidList(List<MaidEntry> entries, boolean allowForceLoad,
                                  ProgressionInfo info) {
        forceLoadAllowed = allowForceLoad;
        progression = info;
        ClientInput.updateSnapshot(entries);

        List<UUID> valid = new ArrayList<>(entries.size());
        for (MaidEntry entry : entries) {
            valid.add(entry.id);
        }
        ClientSelection.retain(valid);

        var screen = Minecraft.getInstance().screen;
        if (screen instanceof MaidManagerScreen terminal) {
            terminal.updateEntries(entries);
        } else if (screen instanceof MaidUpgradeScreen upgrades) {
            // The upgrade screen is a separate Screen, so it has to be told as well - otherwise a
            // purchase would not visibly refresh the row the player just clicked.
            upgrades.updateEntries(entries);
        }
    }

    public static void onActionResult(com.dsh.maidmanager.network.S2CActionResultPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(packet.describe(), true);
        }
    }

    public static boolean forceLoadAllowed() {
        return forceLoadAllowed;
    }

    /** The most recent player-wide progression snapshot. */
    public static ProgressionInfo progression() {
        return progression;
    }
}
