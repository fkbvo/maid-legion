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
 * Checks that a paid-for revive survives a server restart.
 *
 * <p>The revive payment is taken when the cast <em>starts</em>, so the in-flight cast has to be
 * persisted. It originally lived in a static map, which meant a restart mid-cast came back with
 * the materials spent, no revive running, and nothing in the log to explain it - the player
 * simply lost the payment. These tests pin the save/load round trip that fixes that.
 *
 * <p>{@link MaidDeathStorage} is used directly rather than through
 * {@code MaidManagerService}: the service imports {@code EntityMaid}, and loading TLM outside a
 * real FML runtime throws {@code IncompatibleClassChangeError}. {@code MaidDeathStorage} itself
 * touches no TLM class, so it can be exercised as a plain object.
 */
public class MaidDeathStorageTest {

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

        MaidDeathStorage after = MaidDeathStorage.load(before.save(new CompoundTag()));

        MaidDeathStorage.DeadMaid record = after.get(owner, maid);
        assertNotNull("the death record must survive a save/load cycle", record);
        assertEquals("Marisa", record.name());
        assertEquals(1, record.deathCount());
        assertTrue(record.withItems());
        assertEquals("the whole maid NBT must round-trip, or reviving loses her gear",
                42, record.data().getInt("MaidExperience"));
    }

    /**
     * The actual regression: a cast in progress at shutdown must still be there at startup.
     */
    @Test
    public void pendingReviveSurvivesSaveAndLoad() {
        UUID maid = uuid();
        long readyAt = 12345L;

        MaidDeathStorage before = new MaidDeathStorage();
        before.startRevive(maid, readyAt, false);
        assertTrue(before.isRevivePending(maid));

        MaidDeathStorage after = MaidDeathStorage.load(before.save(new CompoundTag()));

        assertTrue("an in-flight revive must survive a restart", after.isRevivePending(maid));
        MaidDeathStorage.PendingRevive pending = after.revivePending(maid);
        assertNotNull(pending);
        assertEquals("the ready tick must be preserved, or the cast restarts", readyAt,
                pending.readyAtTick());
        assertFalse("the payment method must be remembered, so a refund gives the right items",
                pending.useShrines());
    }

    /** A shrine-paid cast must remember that, so a refund returns shrines and not materials. */
    @Test
    public void shrinePaidReviveRemembersItsPaymentMethod() {
        UUID maid = uuid();
        MaidDeathStorage before = new MaidDeathStorage();
        before.startRevive(maid, 999L, true);

        MaidDeathStorage after = MaidDeathStorage.load(before.save(new CompoundTag()));
        assertTrue(after.revivePending(maid).useShrines());
    }

    @Test
    public void clearingAReviveIsAlsoPersisted() {
        UUID maid = uuid();
        MaidDeathStorage before = new MaidDeathStorage();
        before.startRevive(maid, 1L, false);
        before.clearRevive(maid);

        MaidDeathStorage after = MaidDeathStorage.load(before.save(new CompoundTag()));
        assertFalse("a completed revive must not come back after a restart",
                after.isRevivePending(maid));
    }

    /**
     * Deaths accumulate across deaths and are deliberately not reset by reviving, because the
     * cast time scales with them. Reviving must not quietly zero the streak.
     */
    @Test
    public void deathCountKeepsAccumulating() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidDeathStorage storage = new MaidDeathStorage();

        storage.recordDeath(owner, maid, maidTag(), "Marisa", true);
        storage.recordDeath(owner, maid, maidTag(), "Marisa", false);
        MaidDeathStorage afterTwo = MaidDeathStorage.load(storage.save(new CompoundTag()));
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

    /** Two players' records must not bleed into each other. */
    @Test
    public void recordsAreScopedToTheirOwner() {
        UUID alice = uuid();
        UUID bob = uuid();
        UUID maidsMaid = uuid();

        MaidDeathStorage storage = new MaidDeathStorage();
        storage.recordDeath(alice, maidsMaid, maidTag(), "Marisa", true);

        MaidDeathStorage after = MaidDeathStorage.load(storage.save(new CompoundTag()));
        assertNotNull(after.get(alice, maidsMaid));
        assertNull("another player must not see or revive this maid",
                after.get(bob, maidsMaid));
    }

    /** A malformed or unknown-maid key must be skipped, not throw during load. */
    @Test
    public void malformedKeysAreSkipped() {
        CompoundTag tag = new CompoundTag();
        CompoundTag players = new CompoundTag();
        CompoundTag bad = new CompoundTag();
        // Entry with no MaidId / MaidData at all.
        players.put("not-a-uuid", new CompoundTag());
        players.put(UUID.randomUUID().toString(),
                new net.minecraft.nbt.ListTag());
        tag.put("Players", players);

        CompoundTag revives = new CompoundTag();
        revives.put("also-not-a-uuid", new CompoundTag());
        tag.put("PendingRevives", revives);

        MaidDeathStorage loaded = MaidDeathStorage.load(tag);
        assertNotNull("loading malformed data must not throw", loaded);
        assertTrue(loaded.pendingRevives().isEmpty());
    }
}
