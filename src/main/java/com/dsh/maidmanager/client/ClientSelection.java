package com.dsh.maidmanager.client;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The set of maids the player has ticked in the terminal.
 *
 * <p>Lives outside the Screen so that the selection survives closing the GUI and so the
 * V/B hotkeys can act on it while the GUI is closed.
 */
public final class ClientSelection {
    private static final Set<UUID> SELECTED = new LinkedHashSet<>();

    private ClientSelection() {
    }

    public static Set<UUID> get() {
        return Collections.unmodifiableSet(SELECTED);
    }

    public static boolean isSelected(UUID id) {
        return SELECTED.contains(id);
    }

    public static void toggle(UUID id) {
        if (!SELECTED.remove(id)) {
            SELECTED.add(id);
        }
    }

    public static void set(UUID id, boolean selected) {
        if (selected) {
            SELECTED.add(id);
        } else {
            SELECTED.remove(id);
        }
    }

    public static void clear() {
        SELECTED.clear();
    }

    /** Keeps only ids that still exist in the latest snapshot. */
    public static void retain(java.util.Collection<UUID> valid) {
        SELECTED.retainAll(valid);
    }
}
