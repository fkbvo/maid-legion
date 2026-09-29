package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.MaidState;
import com.dsh.maidmanager.logic.ToggleRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins what the single summon/recall hotkey does to a set of ticked maids.
 *
 * <p>These tests exist because the rule below was wrong in a way nothing caught. A ticked maid
 * TLM has unloaded was filed with the maids being <em>put away</em>, so the key sent a
 * {@code STORE} request for a maid who has no loaded entity to store, and it could only ever
 * fail. The suite was fully green, because the routing lived in a client class that needs
 * Minecraft and NeoForge to load at all - so the fix moved it into {@link ToggleRouter}, which
 * is pure and is exercised here.
 *
 * <p>Pure logic only: no Minecraft runtime, no TLM. {@link MaidEntry} is TLM-free, which is why
 * the tests build entries directly rather than going through the service layer.
 */
public class ToggleRouterTest {

    private static MaidEntry entry(MaidState state, boolean forceLoad) {
        return new MaidEntry(UUID.randomUUID(), Component.literal("Maid"), state,
                "minecraft:overworld", new BlockPos(0, 64, 0), 20.0F, 20.0F,
                forceLoad, true, true);
    }

    /**
     * The regression. An unloaded maid whose switch is on must be summoned, never stored.
     *
     * <p>{@code store()} requires a loaded entity, so the old routing could only ever produce a
     * failure: the maid would never move and the player would be told "0 succeeded, 1 failed".
     */
    @Test
    public void unloadedMaidWithSwitchOnIsSummonedNotStored() {
        MaidEntry unloaded = entry(MaidState.UNLOADED, true);

        ToggleRouter.Plan plan = ToggleRouter.plan(List.of(unloaded));

        assertEquals("an unloaded but reachable maid must be brought back",
                ToggleRouter.Direction.SUMMON, plan.direction());
        assertEquals(List.of(unloaded.id), plan.targets());
    }

    /** With the switch off she is genuinely out of reach, so the key must not pretend. */
    @Test
    public void unloadedMaidWithSwitchOffIsIgnored() {
        ToggleRouter.Plan plan = ToggleRouter.plan(List.of(entry(MaidState.UNLOADED, false)));

        assertEquals(ToggleRouter.Direction.NOTHING, plan.direction());
        assertTrue("nothing may be sent at all", plan.isEmpty());
    }

    /** A maid we stored is released from NBT - the cheapest case, and it needs no chunk. */
    @Test
    public void storedMaidIsSummoned() {
        MaidEntry stored = entry(MaidState.STORED, false);

        ToggleRouter.Plan plan = ToggleRouter.plan(List.of(stored));

        assertEquals(ToggleRouter.Direction.SUMMON, plan.direction());
        assertEquals(List.of(stored.id), plan.targets());
    }

    /** A maid standing in the world is the only thing this key puts away. */
    @Test
    public void presentMaidIsStored() {
        MaidEntry present = entry(MaidState.PRESENT, false);

        ToggleRouter.Plan plan = ToggleRouter.plan(List.of(present));

        assertEquals(ToggleRouter.Direction.STORE, plan.direction());
        assertEquals(List.of(present.id), plan.targets());
    }

    /**
     * Summon wins a mixed selection.
     *
     * <p>Bringing a maid back is the unambiguous reading of a key press, and the present maid
     * will still be there for the next press.
     */
    @Test
    public void summonWinsOverStore() {
        MaidEntry stored = entry(MaidState.STORED, false);
        MaidEntry present = entry(MaidState.PRESENT, false);

        ToggleRouter.Plan plan = ToggleRouter.plan(List.of(present, stored));

        assertEquals(ToggleRouter.Direction.SUMMON, plan.direction());
        assertEquals("only the maid being brought back is targeted",
                List.of(stored.id), plan.targets());
    }

    /**
     * A fallen maid is never touched by this key.
     *
     * <p>She can legitimately appear in the selection: the client keeps a selection whose ids
     * remain in the snapshot, and a dead maid does remain in it. Reviving her costs materials,
     * so a movement hotkey must never be able to trigger it.
     */
    @Test
    public void fallenMaidIsNeverActedOn() {
        MaidEntry dead = entry(MaidState.DEAD, false);

        ToggleRouter.Plan plan = ToggleRouter.plan(List.of(dead));

        assertEquals(ToggleRouter.Direction.NOTHING, plan.direction());
        assertTrue("reviving is her badge's job, not this key's", plan.isEmpty());
    }

    /** A dead maid in a mixed selection must not corrupt the batch it travels in. */
    @Test
    public void fallenMaidDoesNotJoinABatch() {
        MaidEntry present = entry(MaidState.PRESENT, false);
        MaidEntry dead = entry(MaidState.DEAD, false);

        ToggleRouter.Plan plan = ToggleRouter.plan(List.of(dead, present));

        assertEquals(ToggleRouter.Direction.STORE, plan.direction());
        assertEquals(List.of(present.id), plan.targets());
        assertFalse("the dead maid must be absent from the targets",
                plan.targets().contains(dead.id));
    }

    /** An empty selection is not an invitation to act on everything. */
    @Test
    public void emptySelectionDoesNothing() {
        ToggleRouter.Plan plan = ToggleRouter.plan(List.of());

        assertEquals(ToggleRouter.Direction.NOTHING, plan.direction());
        assertTrue(plan.isEmpty());
    }
}
