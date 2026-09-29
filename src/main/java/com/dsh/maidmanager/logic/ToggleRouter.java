package com.dsh.maidmanager.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides what the single summon/recall hotkey does to the ticked maids.
 *
 * <p><b>Why this is its own class.</b> The routing used to live inside the client input class,
 * which imports {@code Minecraft} and NeoForge key mappings and therefore cannot be loaded in a
 * plain JUnit run. That is how a routing bug survived a fully green test suite: unloaded maids
 * were filed with the ones being put away, so a press produced a {@code STORE} request for a
 * maid who has no loaded entity to store, and it could only ever fail. The rule is pure - it
 * only reads a {@link MaidState} and a flag - so it lives here where it can be tested.
 *
 * <p>The direction is decided at press time rather than stored, because one key covers both:
 * summon if there is anything to bring back, otherwise put away what is standing around.
 */
public final class ToggleRouter {

    /** What a press of the summon/recall key should do. */
    public enum Direction {
        /** Bring maids to the player: release stored ones, force-load reachable unloaded ones. */
        SUMMON,
        /** Put standing maids away into our own storage. */
        STORE,
        /** Nothing in the selection can be acted on. */
        NOTHING
    }

    /**
     * The chosen direction together with the exact maids it applies to.
     *
     * <p>Both come from one pass, so the direction can never disagree with the targets - which
     * is the failure mode that produced the original bug.
     */
    public record Plan(Direction direction, List<UUID> targets) {
        public boolean isEmpty() {
            return targets.isEmpty();
        }
    }

    private ToggleRouter() {
    }

    /**
     * Classifies a selection into the action to take and the maids to take it on.
     *
     * <p>Summon wins when both are possible: bringing a maid back is the unambiguous reading of
     * "I pressed the key", and it is the direction with a cost the player is waiting on.
     */
    public static Plan plan(List<MaidEntry> scope) {
        List<UUID> toSummon = new ArrayList<>();
        List<UUID> toStore = new ArrayList<>();
        for (MaidEntry entry : scope) {
            switch (entry.state) {
                // Already ours: released straight from NBT, no chunk loading needed.
                case STORED -> toSummon.add(entry.id);
                // TLM still knows her but she is not loaded, so the force-load switch is the
                // only way to reach her. This is the case that used to be mis-filed.
                case UNLOADED -> {
                    if (entry.forceLoad) {
                        toSummon.add(entry.id);
                    }
                }
                // Standing in the world: this press means "put her away".
                case PRESENT -> toStore.add(entry.id);
                // A fallen maid is revived from her own status badge, never from this key. She
                // can still appear here, because the client keeps a selection whose ids remain
                // in the snapshot and a dead maid does remain in it.
                case DEAD -> {
                }
            }
        }
        if (!toSummon.isEmpty()) {
            return new Plan(Direction.SUMMON, List.copyOf(toSummon));
        }
        if (!toStore.isEmpty()) {
            return new Plan(Direction.STORE, List.copyOf(toStore));
        }
        return new Plan(Direction.NOTHING, List.of());
    }
}
