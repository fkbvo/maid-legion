package com.dsh.maidmanager.logic;

/**
 * The three states a maid can be in, as seen by the terminal.
 *
 * <p>These correspond directly to what was verified about TLM:
 * <ul>
 *   <li>{@link #PRESENT} - an {@code EntityMaid} exists in a loaded level.</li>
 *   <li>{@link #STORED} - we hold the maid's NBT in {@link MaidStorage}; it can be
 *       released without loading any chunk.</li>
 *   <li>{@link #UNLOADED} - TLM still has the maid in its world data (its chunk is
 *       unloaded, or it is in another dimension). Only summonable when the per-maid
 *       force-load switch is on.</li>
 * </ul>
 */
public enum MaidState {
    PRESENT,
    STORED,
    UNLOADED;

    public String translationKey() {
        return "gui.maid_legion.state." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
