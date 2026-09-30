package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.ReviveCast;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the shrine-revival cast time and the cooling of a maid's death heat.
 *
 * <p>The rule the player asked for is "only deaths close together lengthen the cast, and it
 * recovers after a while", which is two behaviours that are easy to get subtly wrong: a decay
 * that never actually reaches zero, or a cast that grows without a ceiling. Both would be
 * invisible in play until someone had a very bad run.
 */
public class ReviveCastTest {

    private static final long MINUTE = 60L * 1000L;

    // ------------------------------------------------------------------
    // Decay
    // ------------------------------------------------------------------

    @Test
    public void heatDoesNotCoolBeforeTheWindowElapses() {
        long now = 1_000_000L;
        assertEquals(3, ReviveCast.decayHeat(3, now - MINUTE, now));
        assertEquals(3, ReviveCast.decayHeat(3, now - (ReviveCast.HEAT_DECAY_MS - 1L), now));
    }

    @Test
    public void heatCoolsByOnePerFullWindow() {
        long now = 10_000_000L;
        assertEquals(2, ReviveCast.decayHeat(3, now - ReviveCast.HEAT_DECAY_MS, now));
        assertEquals(1, ReviveCast.decayHeat(3, now - 2 * ReviveCast.HEAT_DECAY_MS, now));
        assertEquals(0, ReviveCast.decayHeat(3, now - 3 * ReviveCast.HEAT_DECAY_MS, now));
    }

    @Test
    public void heatStopsAtZeroNoMatterHowLongItHasBeen() {
        // A maid who died a month ago must not come back with negative heat.
        long now = 100L * 24L * 60L * MINUTE;
        assertEquals(0, ReviveCast.decayHeat(5, 0L, now));
        assertEquals(0, ReviveCast.decayHeat(1, 0L, now));
    }

    @Test
    public void heatOfZeroOrLessStaysZero() {
        assertEquals(0, ReviveCast.decayHeat(0, 12345L, 99999L));
        assertEquals(0, ReviveCast.decayHeat(-4, 12345L, 99999L));
    }

    @Test
    public void aBackwardsClockDoesNotHandOutAFreeReset() {
        // If the stored timestamp is in the future (clock skew, or a copied save), the heat is
        // kept rather than cooled. Resetting here would let a player dodge the penalty.
        long now = 1_000L;
        assertEquals(4, ReviveCast.decayHeat(4, now + MINUTE, now));
        assertEquals(4, ReviveCast.decayHeat(4, now, now));
    }

    @Test
    public void decayIsMonotonicInElapsedTime() {
        long at = 0L;
        int previous = Integer.MAX_VALUE;
        // Six windows is enough for heat 6 to reach zero; five would only get to one.
        for (long elapsed = 0; elapsed <= 6 * ReviveCast.HEAT_DECAY_MS; elapsed += MINUTE) {
            int heat = ReviveCast.decayHeat(6, at, elapsed);
            assertTrue("heat must never rise as time passes", heat <= previous);
            previous = heat;
        }
        assertEquals("six windows must cool heat 6 to nothing", 0, previous);
    }

    // ------------------------------------------------------------------
    // Cast time
    // ------------------------------------------------------------------

    @Test
    public void oneIsolatedDeathUsesTheBaseCast() {
        assertEquals(ReviveCast.CAST_BASE_TICKS, ReviveCast.castTicksFor(1));
    }

    @Test
    public void coldOrNeverDiedAlsoUsesTheBaseCast() {
        // Heat 0 means "she has cooled down"; she must not be faster than a first-time death.
        assertEquals(ReviveCast.CAST_BASE_TICKS, ReviveCast.castTicksFor(0));
    }

    @Test
    public void eachExtraPointOfHeatAddsOneStep() {
        assertEquals(ReviveCast.CAST_BASE_TICKS + ReviveCast.CAST_PER_HEAT_TICKS,
                ReviveCast.castTicksFor(2));
        assertEquals(ReviveCast.CAST_BASE_TICKS + 2 * ReviveCast.CAST_PER_HEAT_TICKS,
                ReviveCast.castTicksFor(3));
    }

    @Test
    public void theCastIsCappedSoABadRunCannotLockThePlayerOut() {
        assertEquals(ReviveCast.CAST_MAX_TICKS, ReviveCast.castTicksFor(10));
        assertEquals(ReviveCast.CAST_MAX_TICKS, ReviveCast.castTicksFor(50));
        assertEquals(ReviveCast.CAST_MAX_TICKS, ReviveCast.castTicksFor(Integer.MAX_VALUE));
    }

    @Test
    public void castTimeNeverDecreasesAsHeatRises() {
        int previous = 0;
        for (int heat = 0; heat <= 40; heat++) {
            int ticks = ReviveCast.castTicksFor(heat);
            assertTrue("cast must not shorten as heat rises", ticks >= previous);
            assertTrue("cast must stay within bounds",
                    ticks >= ReviveCast.CAST_BASE_TICKS && ticks <= ReviveCast.CAST_MAX_TICKS);
            previous = ticks;
        }
    }

    @Test
    public void hugeHeatCannotOverflowIntoANegativeCast() {
        // The step is multiplied as a long before narrowing, so a nonsense heat cannot wrap round
        // to a negative - which would have meant an instant revive.
        assertTrue(ReviveCast.castTicksFor(Integer.MAX_VALUE) > 0);
        assertEquals(ReviveCast.CAST_MAX_TICKS, ReviveCast.castTicksFor(Integer.MAX_VALUE));
    }

    // ------------------------------------------------------------------
    // Combined
    // ------------------------------------------------------------------

    @Test
    public void dyingRepeatedlyInsideTheWindowRaisesTheCast() {
        long t0 = 5_000_000L;
        int heat = 0;
        long at = 0L;

        heat = ReviveCast.heatAfterDeath(heat, at, t0);
        assertEquals("first death", 1, heat);
        assertEquals(ReviveCast.CAST_BASE_TICKS, ReviveCast.castTicksFor(heat));

        // Second death one minute later: still inside the window, so heat climbs.
        at = t0;
        heat = ReviveCast.heatAfterDeath(heat, at, t0 + MINUTE);
        assertEquals("second death in the same window", 2, heat);
        assertEquals(ReviveCast.CAST_BASE_TICKS + ReviveCast.CAST_PER_HEAT_TICKS,
                ReviveCast.castTicksFor(heat));
    }

    @Test
    public void waitingOutTheWindowReturnsToTheBaseCast() {
        long t0 = 5_000_000L;
        // She died three times in quick succession, then survived a long time.
        int heat = 3;
        long at = t0 + 2 * MINUTE;
        long later = at + 5 * ReviveCast.HEAT_DECAY_MS;

        int cooled = ReviveCast.decayHeat(heat, at, later);
        assertEquals("fully cooled", 0, cooled);
        // And the next death therefore costs the baseline again.
        int afterDeath = ReviveCast.heatAfterDeath(heat, at, later);
        assertEquals(1, afterDeath);
        assertEquals(ReviveCast.CAST_BASE_TICKS, ReviveCast.castTicksFor(afterDeath));
    }
}
