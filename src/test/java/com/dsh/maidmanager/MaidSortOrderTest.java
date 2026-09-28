package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.MaidState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the terminal's ordering rules.
 *
 * <p>The order has to be fully deterministic: two maids can share a name (the user hit exactly
 * this with two "雾雨魔理沙"), and a non-deterministic order would make rows jump around
 * between refreshes and make a ticked row ambiguous.
 */
public class MaidSortOrderTest {

    /** Mirrors MaidManagerScreen#sort. */
    private static List<MaidEntry> sort(List<MaidEntry> in) {
        List<MaidEntry> out = new ArrayList<>(in);
        out.sort(Comparator
                .comparing((MaidEntry e) -> !e.favourite)
                .thenComparingInt(e -> e.state.ordinal())
                .thenComparing(Comparator.comparingLong((MaidEntry e) -> e.storedAt).reversed())
                .thenComparing(e -> e.name.getString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.id));
        return out;
    }

    private static MaidEntry fav(String name, MaidState state, boolean favourite) {
        return new MaidEntry(UUID.randomUUID(), Component.literal(name), state,
                "minecraft:overworld", BlockPos.ZERO, 20f, 20f, false, true, true, 0L, favourite);
    }

    @Test
    public void starredMaidsArePinnedAboveEverythingIncludingPresentOnes() {
        // A starred "unloaded" maid must still beat an unstarred "present" one: starring is
        // the strongest ordering key by design.
        List<MaidEntry> sorted = sort(List.of(
                fav("present-unstarred", MaidState.PRESENT, false),
                fav("unloaded-starred", MaidState.UNLOADED, true)));

        assertEquals("unloaded-starred", sorted.get(0).name.getString());
        assertEquals("present-unstarred", sorted.get(1).name.getString());
    }

    @Test
    public void withinStarredGroupTheNormalOrderStillApplies() {
        List<MaidEntry> sorted = sort(List.of(
                fav("z-stored", MaidState.STORED, true),
                fav("a-present", MaidState.PRESENT, true)));

        assertEquals("a-present", sorted.get(0).name.getString());
        assertEquals("z-stored", sorted.get(1).name.getString());
    }

    @Test
    public void multipleStarredMaidsKeepDeterministicOrder() {
        MaidEntry one = new MaidEntry(new UUID(1L, 1L), Component.literal("same"),
                MaidState.STORED, "d", BlockPos.ZERO, 20f, 20f, false, true, true, 0L, true);
        MaidEntry two = new MaidEntry(new UUID(2L, 2L), Component.literal("same"),
                MaidState.STORED, "d", BlockPos.ZERO, 20f, 20f, false, true, true, 0L, true);

        assertEquals(one.id, sort(List.of(two, one)).get(0).id);
        assertEquals(one.id, sort(List.of(one, two)).get(0).id);
    }

    private static MaidEntry maid(String name, MaidState state, long storedAt) {
        return new MaidEntry(UUID.randomUUID(), Component.literal(name), state,
                "minecraft:overworld", BlockPos.ZERO, 20f, 20f, false, true, true, storedAt);
    }

    private static MaidEntry maid(String name, MaidState state, long storedAt, UUID fixedId) {
        return new MaidEntry(fixedId, Component.literal(name), state,
                "minecraft:overworld", BlockPos.ZERO, 20f, 20f, false, true, true, storedAt);
    }

    @Test
    public void presentMaidsComeBeforeStoredWhichComeBeforeUnloaded() {
        List<MaidEntry> sorted = sort(List.of(
                maid("C", MaidState.UNLOADED, 0),
                maid("B", MaidState.STORED, 0),
                maid("A", MaidState.PRESENT, 0)));

        assertEquals("A", sorted.get(0).name.getString());
        assertEquals("B", sorted.get(1).name.getString());
        assertEquals("C", sorted.get(2).name.getString());
    }

    @Test
    public void withinStoredNewestComesFirst() {
        List<MaidEntry> sorted = sort(List.of(
                maid("old", MaidState.STORED, 1_000L),
                maid("new", MaidState.STORED, 9_000L),
                maid("mid", MaidState.STORED, 5_000L)));

        assertEquals("new", sorted.get(0).name.getString());
        assertEquals("mid", sorted.get(1).name.getString());
        assertEquals("old", sorted.get(2).name.getString());
    }

    @Test
    public void identicalNamesAreOrderedDeterministicallyByUuid() {
        // Two maids with the same name and the same timestamp: only the UUID can break the tie.
        UUID low = new UUID(1L, 1L);
        UUID high = new UUID(2L, 2L);
        MaidEntry first = maid("雾雨魔理沙", MaidState.STORED, 500L, low);
        MaidEntry second = maid("雾雨魔理沙", MaidState.STORED, 500L, high);

        List<MaidEntry> once = sort(List.of(first, second));
        List<MaidEntry> twice = sort(List.of(second, first));

        assertEquals("order must not depend on input order",
                once.get(0).id, twice.get(0).id);
        assertEquals("lower UUID sorts first", low, once.get(0).id);
    }

    @Test
    public void shortIdDistinguishesIdenticalNames() {
        // This is what the GUI prints so the player can tell two same-named maids apart.
        UUID a = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");
        UUID b = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000");
        MaidEntry one = maid("雾雨魔理沙", MaidState.STORED, 0L, a);
        MaidEntry two = maid("雾雨魔理沙", MaidState.STORED, 0L, b);

        assertEquals("aaaa", one.shortId());
        assertEquals("bbbb", two.shortId());
        assertNotEquals(one.shortId(), two.shortId());
    }

    @Test
    public void sortingIsStableAcrossRepeatedRuns() {
        List<MaidEntry> input = List.of(
                maid("same", MaidState.STORED, 100L),
                maid("same", MaidState.STORED, 100L),
                maid("same", MaidState.STORED, 100L));

        List<UUID> reference = sort(input).stream().map(e -> e.id).toList();
        for (int i = 0; i < 20; i++) {
            assertEquals("order must be reproducible", reference,
                    sort(input).stream().map(e -> e.id).toList());
        }
    }

    @Test
    public void storedTimestampIsCarriedSoTheGuiCanShowElapsedTime() {
        MaidEntry e = maid("x", MaidState.STORED, 123_456L);
        assertEquals(123_456L, e.storedAt);
        assertTrue("a non-stored maid has no timestamp", maid("y", MaidState.PRESENT, 0).storedAt == 0L);
    }
}
