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
 * Pure-logic tests that need no Minecraft runtime.
 *
 * <p>They pin the summon/store eligibility rules, which are the part most likely to regress
 * and the part that decides whether the GUI greys a row out.
 */
public class MaidEntryTest {

    private static MaidEntry entry(MaidState state, boolean forceLoad, float hp, float maxHp) {
        return new MaidEntry(UUID.randomUUID(), Component.literal("Maid"), state,
                "minecraft:overworld", new BlockPos(0, 64, 0), hp, maxHp,
                forceLoad, true, true);
    }

    @Test
    public void presentMaidIsAlwaysSummonableAndStoreable() {
        MaidEntry e = entry(MaidState.PRESENT, false, 20f, 20f);
        assertTrue("a loaded maid can be teleported", e.summonable());
        assertTrue("a loaded maid can be recalled", e.storeable());
    }

    @Test
    public void storedMaidIsSummonableButNotStoreable() {
        MaidEntry e = entry(MaidState.STORED, false, -1f, -1f);
        assertTrue("a stored maid is released from NBT", e.summonable());
        assertFalse("a stored maid has no entity to recall", e.storeable());
    }

    @Test
    public void unloadedMaidDependsOnTheForceLoadSwitch() {
        assertFalse("without the switch an unloaded maid cannot be reached",
                entry(MaidState.UNLOADED, false, -1f, -1f).summonable());
        assertTrue("with the switch she becomes summonable",
                entry(MaidState.UNLOADED, true, -1f, -1f).summonable());
    }

    @Test
    public void unloadedMaidIsNeverStoreable() {
        assertFalse(entry(MaidState.UNLOADED, true, -1f, -1f).storeable());
    }

    @Test
    public void healthFractionIsClampedAndSafeAgainstZeroMax() {
        assertEquals(0.5f, entry(MaidState.PRESENT, false, 10f, 20f).healthFraction(), 0.0001f);
        assertEquals(0f, entry(MaidState.PRESENT, false, 5f, 0f).healthFraction(), 0.0001f);
        assertEquals(1f, entry(MaidState.PRESENT, false, 999f, 20f).healthFraction(), 0.0001f);
    }

    @Test
    public void everyStateHasATranslationKey() {
        for (MaidState state : MaidState.values()) {
            assertTrue(state.translationKey().startsWith("gui.maid_manager.state."));
        }
    }
}
