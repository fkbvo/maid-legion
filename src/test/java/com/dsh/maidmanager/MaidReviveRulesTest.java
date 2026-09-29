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
 *       what the revive path is for - but she must still report as revivable, because that is
 *       what turns her status badge into the revive control.</li>
 *   <li><b>Tick boxes and revive are separate.</b> The tick box feeds the summon/store
 *       batches; revive is a per-row action on the badge. A fallen maid belongs to neither
 *       batch, so she must not be tickable.</li>
 * </ul>
 *
 * <p><b>Why nothing here calls {@code MaidManagerService}.</b> That class imports
 * {@code EntityMaid}, so touching any of its members makes the JVM load and verify TLM, which
 * throws {@code IncompatibleClassChangeError} outside a real FML runtime (TLM's
 * {@code EntityMaid} overrides {@code Entity.getEyeHeight}, final in this mapping set). The
 * rules are therefore asserted against {@link MaidEntry}, which is itself TLM-free.
 */
public class MaidReviveRulesTest {

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

    /**
     * The tick box must exclude fallen maids.
     *
     * <p>Mirrors {@code MaidManagerScreen.Row.selectable()}. Revive is a per-row action on the
     * maid's own status badge, so the tick box no longer has anything to do with her; leaving
     * her tickable would feed a dead id into the summon and store batches, which can only
     * reject it.
     */
    @Test
    public void tickBoxExcludesFallenMaids() {
        for (MaidState state : MaidState.values()) {
            MaidEntry e = entry(state, 1, true);
            boolean tickable = e.summonable() || e.storeable();
            if (state == MaidState.DEAD) {
                assertFalse("a fallen maid revives from her badge, not from the tick box",
                        tickable);
                assertTrue("but she must still advertise revive", e.revivable());
                continue;
            }
            if (state == MaidState.UNLOADED) {
                // This helper builds UNLOADED with force-load off, which is untickable too.
                assertFalse("an unloaded maid with the switch off has nothing to batch",
                        tickable);
                continue;
            }
            assertTrue(state + " must be tickable so summon/store can act on it", tickable);
        }
    }

    /**
     * A fallen maid must be able to advertise revive while refusing the two batch actions.
     *
     * <p>Guards the split that makes the badge control work: the UI decides between the state
     * label and the revive label from {@code revivable()}, and decides whether Summon/Store
     * light up from {@code summonable()}/{@code storeable()}. If death ever became summonable,
     * reviving would be replaced by teleporting a maid who does not exist.
     */
    @Test
    public void deadMaidAdvertisesReviveButNotSummonOrStore() {
        MaidEntry dead = entry(MaidState.DEAD, 1, true);

        assertTrue("revive must be offered for a fallen maid", dead.revivable());
        assertFalse("summon must stay unavailable", dead.summonable());
        assertFalse("store must stay unavailable", dead.storeable());

        MaidEntry alive = entry(MaidState.PRESENT, 0, false);
        assertTrue("a live maid still summons", alive.summonable());
        assertTrue("and stores", alive.storeable());
        assertFalse("but never revives", alive.revivable());
    }
}
