package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.LampDrain;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the arithmetic that moves P-points out of a shrine lamp.
 *
 * <p>Every number here is a balance the player cares about, and the failure modes are opposite
 * in kind: taking too little strands points in a block, taking too much conjures them. Both are
 * pinned, including the awkward edges where a lamp is nearly empty or the bank is nearly full.
 */
public class LampDrainTest {

    // ------------------------------------------------------------------
    // drainable
    // ------------------------------------------------------------------

    @Test
    public void takesEverythingAboveTheReserveWhenTheBankHasRoom() {
        assertEquals(9.1F, LampDrain.drainable(10.0F, 0.9F, 300.0F), 1.0E-4F);
    }

    @Test
    public void neverTakesMoreThanTheBankCanHold() {
        assertEquals(5.0F, LampDrain.drainable(50.0F, 0.0F, 5.0F), 1.0E-4F);
    }

    @Test
    public void leavesTheReserveBehind() {
        // Exactly the reserve: nothing to take, and the lamp keeps its buff running.
        assertEquals(0.0F, LampDrain.drainable(0.9F, 0.9F, 300.0F), 1.0E-4F);
        // Below the reserve: still nothing, and definitely no negative take.
        assertEquals(0.0F, LampDrain.drainable(0.5F, 0.9F, 300.0F), 1.0E-4F);
    }

    @Test
    public void anEmptyOrNegativeLampYieldsNothing() {
        assertEquals(0.0F, LampDrain.drainable(0.0F, 0.0F, 300.0F), 1.0E-4F);
        assertEquals(0.0F, LampDrain.drainable(-8.0F, 0.0F, 300.0F), 1.0E-4F);
    }

    @Test
    public void aFullOrNegativeBankAcceptsNothing() {
        assertEquals(0.0F, LampDrain.drainable(50.0F, 0.0F, 0.0F), 1.0E-4F);
        assertEquals(0.0F, LampDrain.drainable(50.0F, 0.0F, -3.0F), 1.0E-4F);
    }

    @Test
    public void aNegativeReserveIsTreatedAsZeroRatherThanTakingExtra() {
        // "-1 reserve" must not mean "take one more than exists".
        assertEquals(10.0F, LampDrain.drainable(10.0F, -1.0F, 300.0F), 1.0E-4F);
    }

    // ------------------------------------------------------------------
    // reserveFor
    // ------------------------------------------------------------------

    @Test
    public void theReserveIsWhicheverIsLargerOfTheLampsCostAndTheSetting() {
        assertEquals(0.9F, LampDrain.reserveFor(0.9F, 0.0F), 1.0E-4F);
        assertEquals(5.0F, LampDrain.reserveFor(0.9F, 5.0F), 1.0E-4F);
        // An unknown (zero) effect cost must not mean "drain it dry".
        assertEquals(1.0F, LampDrain.reserveFor(0.0F, 1.0F), 1.0E-4F);
    }

    @Test
    public void aNegativeReserveSettingIsClampedToZero() {
        assertEquals(0.0F, LampDrain.reserveFor(0.0F, -5.0F), 1.0E-4F);
        assertEquals(0.9F, LampDrain.reserveFor(0.9F, -5.0F), 1.0E-4F);
    }

    // ------------------------------------------------------------------
    // itemsAffordable
    // ------------------------------------------------------------------

    @Test
    public void allHeldItemsAreTakenWhenTheBankHasRoom() {
        assertEquals(3, LampDrain.itemsAffordable(300.0F, 2.85F, 3));
    }

    @Test
    public void onlyAsManyItemsAsFitAreTaken() {
        // Room for one item at 2.85 each.
        assertEquals(1, LampDrain.itemsAffordable(2.85F, 2.85F, 64));
        // Room for two.
        assertEquals(2, LampDrain.itemsAffordable(5.70F, 2.85F, 64));
        // Just short of two.
        assertEquals(1, LampDrain.itemsAffordable(5.69F, 2.85F, 64));
    }

    @Test
    public void aPartialItemIsNeverCredited() {
        // Rounding up here would hand out points that were never paid for.
        assertEquals(0, LampDrain.itemsAffordable(2.84F, 2.85F, 64));
        assertEquals(0, LampDrain.itemsAffordable(1.0F, 2.85F, 64));
    }

    @Test
    public void nothingHappensWithoutItemsRoomOrAValue() {
        assertEquals(0, LampDrain.itemsAffordable(300.0F, 2.85F, 0));
        assertEquals(0, LampDrain.itemsAffordable(300.0F, 2.85F, -2));
        assertEquals(0, LampDrain.itemsAffordable(0.0F, 2.85F, 64));
        assertEquals(0, LampDrain.itemsAffordable(300.0F, 0.0F, 64));
        assertEquals(0, LampDrain.itemsAffordable(300.0F, -2.85F, 64));
    }

    @Test
    public void aHugeItemCountIsClampedToWhatActuallyFits() {
        // floor(1,000,000 / 0.01) is 100,000,000, well inside int range, so the count is clamped
        // by the bank rather than wrapping negative - which would have meant taking nothing.
        int taken = LampDrain.itemsAffordable(1_000_000.0F, 0.01F, Integer.MAX_VALUE);
        assertEquals(100_000_000, taken);
        assertTrue(taken > 0);

        // And a held count below the affordable amount is what limits it.
        assertEquals(7, LampDrain.itemsAffordable(1_000_000.0F, 0.01F, 7));
    }
}
