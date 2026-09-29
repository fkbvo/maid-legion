package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidDeathStorage;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks that a captured death survives a server restart.
 *
 * <p>This is the whole reason the feature works: TLM's tombstone path pushes a maid's gear
 * <em>out</em> of her before firing {@code MaidTombstoneEvent}, so we snapshot her first and
 * cancel the tombstone. If that snapshot did not persist, a restart would erase every maid
 * waiting to be revived and their equipment with them. These tests pin the save/load round
 * trip, including the full entity NBT.
 *
 * <p>{@link MaidDeathStorage} is used directly rather than through
 * {@code MaidManagerService}: the service imports {@code EntityMaid}, and loading TLM outside a
 * real FML runtime throws {@code IncompatibleClassChangeError}. {@code MaidDeathStorage} itself
 * touches no TLM class, so it can be exercised as a plain object.
 */
public class MaidDeathStorageTest {

    /**
     * 1.21 added a {@code HolderLookup.Provider} to {@code SavedData.load/save}. This data is
     * plain NBT, so the provider is unused - but the signatures require one.
     */
    private static final net.minecraft.core.HolderLookup.Provider PROVIDER =
            net.minecraft.core.RegistryAccess.EMPTY;

    private static UUID uuid() {
        return UUID.randomUUID();
    }

    /** A minimal stand-in for a maid's captured NBT. */
    private static CompoundTag maidTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("CustomName", "Marisa");
        tag.putInt("MaidExperience", 42);
        return tag;
    }

    @Test
    public void deadMaidRecordSurvivesSaveAndLoad() {
        UUID owner = uuid();
        UUID maid = uuid();

        MaidDeathStorage before = new MaidDeathStorage();
        before.recordDeath(owner, maid, maidTag(), "Marisa", true);

        MaidDeathStorage after =
                MaidDeathStorage.load(before.save(new CompoundTag(), PROVIDER), PROVIDER);

        MaidDeathStorage.DeadMaid record = after.get(owner, maid);
        assertNotNull("the death record must survive a save/load cycle", record);
        assertEquals("Marisa", record.name());
        assertEquals(1, record.deathCount());
        assertTrue(record.withItems());
        assertEquals("the whole maid NBT must round-trip, or reviving loses her gear",
                42, record.data().getInt("MaidExperience"));
    }

    /**
     * Deaths accumulate across deaths and are deliberately not reset by reviving, because the
     * panel shows the streak. Reviving must not quietly zero it.
     */
    @Test
    public void deathCountKeepsAccumulating() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidDeathStorage storage = new MaidDeathStorage();

        storage.recordDeath(owner, maid, maidTag(), "Marisa", true);
        storage.recordDeath(owner, maid, maidTag(), "Marisa", false);
        MaidDeathStorage afterTwo =
                MaidDeathStorage.load(storage.save(new CompoundTag(), PROVIDER), PROVIDER);
        assertEquals(2, afterTwo.deathCount(owner, maid));

        // Reviving removes the record but the count is only re-created on the next death.
        afterTwo.remove(owner, maid);
        afterTwo.recordDeath(owner, maid, maidTag(), "Marisa", true);
        assertEquals("a revive must not reset the death streak", 1,
                afterTwo.deathCount(owner, maid));
    }

    @Test
    public void removingADeadMaidClearsHerRecord() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidDeathStorage storage = new MaidDeathStorage();
        storage.recordDeath(owner, maid, maidTag(), "Marisa", true);

        assertNotNull(storage.remove(owner, maid));
        assertNull("a revived maid must no longer be listed as dead", storage.get(owner, maid));
        assertFalse(storage.contains(owner, maid));
    }

    /**
     * A death record must be queryable by owner, because that query is what proves ownership.
     *
     * <p>Regression guard for a bug that only appeared in game. {@code MaidManagerService
     * .ownsMaid} had three ways to recognise a maid - a loaded entity, our own store, or TLM's
     * unloaded-maid list - and a maid we captured on death matches none of them: she has no
     * entity, is not in the store, and TLM has stopped tracking her. The roster is built from
     * the death records though, so the panel happily listed her while every action on her,
     * revive included, was refused as "not your maid".
     *
     * <p>The fix makes {@code ownsMaid} fall back to exactly the {@code contains} query below.
     * If {@code contains} ever stopped agreeing with {@code recordDeath}'s key, dead maids
     * would silently become uncontrollable again.
     */
    @Test
    public void aDeathRecordIsQueryableByOwnerSoItCanProveOwnership() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidDeathStorage storage = new MaidDeathStorage();

        assertFalse("nothing recorded yet", storage.contains(owner, maid));

        storage.recordDeath(owner, maid, maidTag(), "Marisa", true);

        assertTrue("the owner must be able to prove ownership through the death record",
                storage.contains(owner, maid));
        assertFalse("and it must not read as owned by anyone else",
                storage.contains(uuid(), maid));
    }

    /** Two players' records must not bleed into each other. */
    @Test
    public void recordsAreScopedToTheirOwner() {
        UUID alice = uuid();
        UUID bob = uuid();
        UUID maidsMaid = uuid();

        MaidDeathStorage storage = new MaidDeathStorage();
        storage.recordDeath(alice, maidsMaid, maidTag(), "Marisa", true);

        MaidDeathStorage after =
                MaidDeathStorage.load(storage.save(new CompoundTag(), PROVIDER), PROVIDER);
        assertNotNull(after.get(alice, maidsMaid));
        assertNull("another player must not see or revive this maid",
                after.get(bob, maidsMaid));
    }

    /** A malformed or unknown-maid key must be skipped, not throw during load. */
    @Test
    public void malformedKeysAreSkipped() {
        CompoundTag tag = new CompoundTag();
        CompoundTag players = new CompoundTag();
        // Entry with no MaidId / MaidData at all.
        players.put("not-a-uuid", new CompoundTag());
        players.put(UUID.randomUUID().toString(), new net.minecraft.nbt.ListTag());
        tag.put("Players", players);

        MaidDeathStorage loaded = MaidDeathStorage.load(tag, PROVIDER);
        assertNotNull("loading malformed data must not throw", loaded);
        assertEquals("a junk key must not invent a dead maid", 0,
                loaded.list(UUID.randomUUID()).size());
    }

    /**
     * Data written by an older build that still carried a {@code PendingRevives} block must
     * load cleanly. The cast machinery is gone, so the block is simply ignored - but a save
     * made before the removal must not fail to load.
     */
    @Test
    public void legacyPendingRevivesBlockIsIgnored() {
        CompoundTag tag = new CompoundTag();
        tag.put("Players", new CompoundTag());
        CompoundTag revives = new CompoundTag();
        revives.put(UUID.randomUUID().toString(), new CompoundTag());
        tag.put("PendingRevives", revives);

        MaidDeathStorage loaded = MaidDeathStorage.load(tag, PROVIDER);
        assertNotNull("an old save with a PendingRevives block must still load", loaded);
    }
}
