package com.dsh.maidmanager.client;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.MaidState;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Handles server packets on the client. */
public final class ClientPayloadHandlers {
    private static volatile boolean forceLoadAllowed = true;

    private ClientPayloadHandlers() {
    }

    public static void onMaidList(List<MaidEntry> entries, boolean allowForceLoad) {
        forceLoadAllowed = allowForceLoad;
        ClientInput.updateSnapshot(entries);

        List<UUID> valid = new ArrayList<>(entries.size());
        for (MaidEntry entry : entries) {
            valid.add(entry.id);
        }
        ClientSelection.retain(valid);

        var screen = Minecraft.getInstance().screen;
        if (screen instanceof MaidManagerScreen terminal) {
            terminal.updateEntries(entries);
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
}
