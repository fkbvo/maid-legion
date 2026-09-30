package com.dsh.maidmanager.logic;

/**
 * Works out how long a shrine revival has to be channelled.
 *
 * <p>The rule the player asked for: <b>only deaths that come close together make the cast
 * longer, and it recovers once they stop.</b> So a maid's "heat" climbs by one per death and
 * cools by one every {@link #HEAT_DECAY_MS} of not dying.
 *
 * <p>Deliberately free of Minecraft types. The arithmetic here decides how long a player is
 * rooted in place, so it is kept pure and unit tested rather than buried in a tick handler.
 */
public final class ReviveCast {

    /** Heat drops by one for every window of not dying. */
    public static final long HEAT_DECAY_MS = 10L * 60L * 1000L;

    /** Channelling time for a single, isolated death. */
    public static final int CAST_BASE_TICKS = 60;

    /** Added to the cast for every point of heat beyond the first. */
    public static final int CAST_PER_HEAT_TICKS = 60;

    /** Ceiling on the cast, so a bad run cannot lock the player out indefinitely. */
    public static final int CAST_MAX_TICKS = 600;

    private ReviveCast() {
    }

    /**
     * Heat as of {@code now}, after cooling.
     *
     * <p>Called when a maid dies, before her heat is incremented. Also used to report the
     * current heat without touching it.
     *
     * @param heat what was stored the last time she died, or 0 if she has not
     * @param at   the epoch millis of that last death
     * @param now  current epoch millis
     */
    public static int decayHeat(int heat, long at, long now) {
        if (heat <= 0) {
            return 0;
        }
        long elapsed = now - at;
        if (elapsed <= 0L) {
            // Clock skew, or the same instant: keep the heat rather than handing out a free reset.
            return heat;
        }
        long cooled = elapsed / HEAT_DECAY_MS;
        if (cooled <= 0L) {
            return heat;
        }
        if (cooled >= heat) {
            return 0;
        }
        return (int) (heat - cooled);
    }

    /**
     * Channelling time in ticks for a maid at the given heat.
     *
     * <p>One death is the baseline; each further point of heat adds a step, up to the ceiling.
     * Heat of 0 or 1 both give the baseline, so a maid who has never died is not faster than one
     * who died long ago.
     */
    public static int castTicksFor(int heat) {
        int steps = Math.max(0, heat - 1);
        long ticks = CAST_BASE_TICKS + (long) steps * CAST_PER_HEAT_TICKS;
        return (int) Math.min(CAST_MAX_TICKS, ticks);
    }

    /** Heat after one more death, given the stored value and when it was recorded. */
    public static int heatAfterDeath(int storedHeat, long storedAt, long now) {
        return decayHeat(storedHeat, storedAt, now) + 1;
    }
}
