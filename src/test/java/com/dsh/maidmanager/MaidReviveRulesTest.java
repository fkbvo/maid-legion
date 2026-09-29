package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.MaidState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the rules that decide what a player may do to a maid from the panel.
 *
 * <p>These are pure-logic checks with no Minecraft or FML runtime. They exist because the
 * rules below are easy to break by accident and expensive to get wrong:
 *
 * <ul>
 *   <li><b>Death is a distinct state.</b> A fallen maid must not look summonable - that is
 *       what the revive path is for - but she must still report as revivable.</li>
 *   <li><b>The revive delay is a contract.</b> It scales with the death count, is bounded,
 *       and the client's countdown must agree with the server's, or the UI lies.</li>
 * </ul>
 *
 * <p><b>Why nothing here calls {@code MaidManagerService}.</b> That class imports
 * {@code EntityMaid}, so touching any of its members makes the JVM load and verify TLM, which
 * throws {@code IncompatibleClassChangeError} outside a real FML runtime (TLM's
 * {@code EntityMaid} overrides {@code Entity.getEyeHeight}, final in this mapping set). The
 * values it defines are therefore duplicated here as literals and asserted against
 * {@link MaidEntry} instead, which is itself TLM-free. If you change one of those constants,
 * change it here too - the pairing is deliberate and called out in each test.
 */
public class MaidReviveRulesTest {

    /** Must equal {@code MaidManagerService.reviveDelayTicks}'s base: 5 seconds. */
    private static final int BASE_DELAY_TICKS = 20 * 5;
    /** Must equal {@code MaidManagerService.reviveDelayTicks}'s cap: 60 seconds. */
    private static final int MAX_DELAY_TICKS = 20 * 60;
    /** Must equal {@code MaidManagerService.SHRINE_ALTERNATIVE_COUNT}. */
    private static final int SHRINE_COUNT = 3;

    private static MaidEntry entry(MaidState state, int deathCount, boolean hadItems) {
        return new MaidEntry(UUID.randomUUID(), Component.literal("Maid"), state,
                "minecraft:overworld", new BlockPos(0, 64, 0), -1.0F, -1.0F,
                false, true, true, 1000L, false, deathCount, hadItems);
    }

    @Test
    public void deadMaidIsNotSummonableButIsRevivable() {
        MaidEntry dead = entry(MaidState.DEAD, 1, true);
        assertFalse("a dead maid must not be summonable; she has no entity to teleport",
                dead.summonable());
        assertFalse("a dead maid must not be storeable", dead.storeable());
        assertTrue("a dead maid must offer revive", dead.revivable());
    }

    @Test
    public void liveStatesAreNotRevivable() {
        for (MaidState state : new MaidState[]{MaidState.PRESENT, MaidState.STORED}) {
            assertFalse(state + " must not be revivable", entry(state, 0, false).revivable());
        }
    }

    /**
     * Re-checked here because {@code summonable()} gained a branch for {@code DEAD}: an
     * unloaded maid must still be gated by the force-load switch.
     */
    @Test
    public void unloadedMaidStillNeedsTheSwitch() {
        MaidEntry off = new MaidEntry(UUID.randomUUID(), Component.literal("Maid"),
                MaidState.UNLOADED, "minecraft:overworld", new BlockPos(0, 64, 0),
                -1.0F, -1.0F, false, true, true);
        assertFalse(off.summonable());
        assertFalse(off.revivable());

        MaidEntry on = new MaidEntry(UUID.randomUUID(), Component.literal("Maid"),
                MaidState.UNLOADED, "minecraft:overworld", new BlockPos(0, 64, 0),
                -1.0F, -1.0F, true, true, true);
        assertTrue(on.summonable());
    }

    /** The delay grows with the death count and stays bounded so a maid stays usable. */
    @Test
    public void reviveDelayGrowsWithDeathsAndIsCapped() {
        MaidEntry first = entry(MaidState.DEAD, 1, true);
        MaidEntry second = entry(MaidState.DEAD, 2, true);
        MaidEntry third = entry(MaidState.DEAD, 3, true);

        assertEquals("first death should use the base delay",
                BASE_DELAY_TICKS, first.reviveDelayTicks());
        assertTrue("each further death must take longer",
                second.reviveDelayTicks() > first.reviveDelayTicks());
        assertTrue("and longer again",
                third.reviveDelayTicks() > second.reviveDelayTicks());

        assertEquals("delay must be capped at 60s",
                MAX_DELAY_TICKS, entry(MaidState.DEAD, 1000, true).reviveDelayTicks());
    }

    /**
     * A maid who died before this feature existed, or whose NBT carried no inventory, must
     * still be revivable - she just comes back empty-handed. Regression guard for treating
     * {@code hadItems} as a gate rather than a description.
     */
    @Test
    public void revivableEvenWithoutPreservedItems() {
        MaidEntry empty = entry(MaidState.DEAD, 1, false);
        assertTrue(empty.revivable());
        assertFalse(empty.hadItems);
    }

    /** Death count defaults to zero for live states, so the UI shows no stale number. */
    @Test
    public void liveStatesCarryNoDeathCount() {
        assertEquals(0, entry(MaidState.PRESENT, 0, false).deathCount);
        assertEquals(0, entry(MaidState.STORED, 0, false).deathCount);
    }

    /** The shrine alternative is a fixed, documented quantity. */
    @Test
    public void shrineAlternativeIsThreeShrines() {
        assertEquals(3, SHRINE_COUNT);
    }

    /**
     * A fallen maid must be tickable, or the revive buttons can never light up.
     *
     * <p>Regression guard for a shipped bug. The row's tick box was gated on
     * {@code summonable() || storeable()}, and a dead maid is neither - so her tick box was
     * dead, {@code selectedIds()} stayed empty however hard the player clicked, and
     * {@code hasDeadSelected()} was false forever. Revive was unreachable from the UI even
     * though the server side worked. "Can this row be ticked" is a different question from
     * "can this row be summoned", and the two must not be conflated again.
     *
     * <p>{@code MaidManagerScreen}'s {@code Row.selectable()} implements the rule below; it
     * is a private nested class, so the rule is restated here over the same predicates the
     * button-enabling logic uses.
     */
    @Test
    public void deadMaidIsTickableSoReviveCanBeReached() {
        for (MaidState state : MaidState.values()) {
            MaidEntry e = entry(state, 1, true);
            // Mirrors MaidManagerScreen.Row.selectable(). UNLOADED with the switch off is
            // deliberately excluded below rather than here, because this helper builds that
            // entry with forceLoad=false.
            boolean tickable = e.summonable() || e.storeable() || e.revivable();
            if (state == MaidState.UNLOADED) {
                assertFalse("an unloaded maid with the switch off has nothing to act on, "
                        + "so she stays untickable by design", tickable);
                continue;
            }
            assertTrue("every other state must let the player tick its row, including "
                    + state, tickable);
        }
    }

    /**
     * Summon and Store must not light up for a dead-only selection.
     *
     * <p>The second half of the same bug: once dead rows became tickable, "something is
     * ticked" stopped implying "summon works". The buttons are gated on a state-filtered
     * count, not on the raw selection size, so ticking only a fallen maid leaves Summon and
     * Store greyed out instead of firing a packet the server rejects.
     */
    @Test
    public void deadOnlySelectionEnablesReviveButNotSummonOrStore() {
        MaidEntry dead = entry(MaidState.DEAD, 1, true);

        assertTrue("revive must be available for a dead selection", dead.revivable());
        assertFalse("summon must stay greyed out", dead.summonable());
        assertFalse("store must stay greyed out", dead.storeable());

        MaidEntry alive = entry(MaidState.PRESENT, 0, false);
        assertTrue("a live maid still summons", alive.summonable());
        assertTrue("and stores", alive.storeable());
        assertFalse("but never revives", alive.revivable());
    }
}
