package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.MaidState;
import com.dsh.maidmanager.logic.MaidUpgrade;
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
            assertTrue(state.translationKey().startsWith("gui.maid_legion.state."));
        }
    }

    // ------------------------------------------------------------------
    // Progression fields
    // ------------------------------------------------------------------

    @Test
    public void progressionDefaultsToNothingForTheOlderConstructor() {
        // Every existing call site uses a shorter constructor. Those entries must mean "no
        // experience, no upgrades" rather than blowing up or carrying stale numbers.
        MaidEntry e = entry(MaidState.PRESENT, false, 20f, 20f);
        assertEquals(0, e.experience);
        assertEquals(MaidUpgrade.values().length, e.levels.length);
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            assertEquals(0, e.levelOf(upgrade));
        }
    }

    @Test
    public void levelsAreReadBackByUpgrade() {
        int[] levels = new int[MaidUpgrade.values().length];
        levels[MaidUpgrade.ATTACK.ordinal()] = 37;
        levels[MaidUpgrade.SPEED.ordinal()] = 100;
        MaidEntry e = new MaidEntry(UUID.randomUUID(), Component.literal("Maid"),
                MaidState.PRESENT, "minecraft:overworld", new BlockPos(0, 64, 0), 20f, 20f,
                false, true, true, 0L, false, 0, true, 12_345, levels);

        assertEquals(12_345, e.experience);
        assertEquals(37, e.levelOf(MaidUpgrade.ATTACK));
        assertEquals(100, e.levelOf(MaidUpgrade.SPEED));
        assertEquals(0, e.levelOf(MaidUpgrade.ARMOR));
    }

    @Test
    public void aShortOrLongLevelArrayIsNormalisedNotFatal() {
        // A client on an older build sends a shorter array; one on a newer build sends a longer
        // one. Either way the row has to render.
        int[] tooShort = {5, 6};
        MaidEntry a = new MaidEntry(UUID.randomUUID(), Component.literal("Maid"),
                MaidState.PRESENT, "minecraft:overworld", new BlockPos(0, 64, 0), 20f, 20f,
                false, true, true, 0L, false, 0, true, 0, tooShort);
        assertEquals(MaidUpgrade.values().length, a.levels.length);
        assertEquals(5, a.levelOf(MaidUpgrade.values()[0]));

        int[] tooLong = new int[MaidUpgrade.values().length + 5];
        MaidEntry b = new MaidEntry(UUID.randomUUID(), Component.literal("Maid"),
                MaidState.PRESENT, "minecraft:overworld", new BlockPos(0, 64, 0), 20f, 20f,
                false, true, true, 0L, false, 0, true, 0, tooLong);
        assertEquals(MaidUpgrade.values().length, b.levels.length);

        // Null is tolerated too, since the old constructor passes it straight through.
        MaidEntry c = new MaidEntry(UUID.randomUUID(), Component.literal("Maid"),
                MaidState.PRESENT, "minecraft:overworld", new BlockPos(0, 64, 0), 20f, 20f,
                false, true, true, 0L, false, 0, true, 0, null);
        assertEquals(0, c.levelOf(MaidUpgrade.ATTACK));
    }

    @Test
    public void negativeLevelsAndExperienceAreClampedAway() {
        int[] levels = new int[MaidUpgrade.values().length];
        levels[MaidUpgrade.ATTACK.ordinal()] = -9;
        MaidEntry e = new MaidEntry(UUID.randomUUID(), Component.literal("Maid"),
                MaidState.PRESENT, "minecraft:overworld", new BlockPos(0, 64, 0), 20f, 20f,
                false, true, true, 0L, false, 0, true, -50, levels);
        assertEquals(0, e.experience);
        assertEquals(0, e.levelOf(MaidUpgrade.ATTACK));
    }

    @Test
    public void onlyUnloadedMaidsRefuseUpgrades() {
        // Unloaded maids' NBT sits inside TLM's own world data, which we cannot write to. Every
        // other state is reachable, including a fallen maid waiting to be revived.
        assertFalse(entry(MaidState.UNLOADED, true, -1f, -1f).upgradable());
        assertTrue(entry(MaidState.PRESENT, false, 20f, 20f).upgradable());
        assertTrue(entry(MaidState.STORED, false, -1f, -1f).upgradable());
        assertTrue(entry(MaidState.DEAD, false, -1f, -1f).upgradable());
    }

    @Test
    public void withProgressionKeepsEveryOtherField() {
        MaidEntry original = entry(MaidState.PRESENT, true, 12f, 20f);
        int[] levels = new int[MaidUpgrade.values().length];
        levels[MaidUpgrade.LUCK.ordinal()] = 7;

        MaidEntry decorated = original.withProgression(999, levels);
        assertEquals(original.id, decorated.id);
        assertEquals(original.state, decorated.state);
        assertEquals(original.pos, decorated.pos);
        assertEquals(original.health, decorated.health, 0.0001f);
        assertEquals(original.forceLoad, decorated.forceLoad);
        assertEquals(original.favourite, decorated.favourite);
        assertEquals(999, decorated.experience);
        assertEquals(7, decorated.levelOf(MaidUpgrade.LUCK));
    }
}
