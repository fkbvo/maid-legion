package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidDeathStorage;
import com.dsh.maidmanager.logic.MaidProgressStorage;
import com.dsh.maidmanager.logic.MaidRegistry;
import com.dsh.maidmanager.logic.MaidStorage;
import com.dsh.maidmanager.logic.MaidUpgrade;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the one-time adoption of data written before the mod was renamed.
 *
 * <p>This is the highest-stakes logic in the mod: the four saved-data ids changed from
 * {@code maid_legion_*} to {@code touhou_maid_legion_*}, and if adoption misfires a player's
 * whole legion - enrolled maids, stored maids' inventories, captured deaths, bought upgrade
 * levels and banked P-points - silently comes back empty.
 *
 * <p>The two properties that matter are pinned here directly:
 * <ul>
 *   <li>adoption happens <b>only</b> into an empty store, so it can never overwrite data the
 *       renamed build has already written;</li>
 *   <li>adoption is <b>idempotent</b>, so a second load cannot duplicate anything.</li>
 * </ul>
 *
 * <p>{@code adoptIfEmpty} is package-private precisely so this can run without a server; the
 * surrounding {@code get(MinecraftServer)} glue is exercised in game instead.
 *
 * <p>These classes touch only NBT, never {@code ItemStack} or {@code EntityMaid}, which is what
 * lets them load outside a running game.
 */
public class LegacyDataMigrationTest {

    private static UUID uuid() {
        return UUID.randomUUID();
    }

    /** A registry with one enrolled maid, the way a pre-rename world would hold one. */
    private static MaidRegistry populatedRegistry(UUID owner, UUID maid) {
        MaidRegistry registry = new MaidRegistry();
        registry.setEnrolled(owner, maid, true);
        return registry;
    }

    // ------------------------------------------------------------------
    // MaidRegistry
    // ------------------------------------------------------------------

    @Test
    public void registryAdoptsIntoAnEmptyInstance() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidRegistry live = new MaidRegistry();
        assertTrue("an empty registry should adopt", live.isEmpty());

        assertTrue(MaidRegistry.adoptIfEmpty(live, populatedRegistry(owner, maid)));
        assertTrue("the enrolled maid must survive", live.isEnrolled(owner, maid));
        assertFalse("and nothing else should appear", live.isEmpty());
    }

    @Test
    public void registryDoesNotAdoptOverExistingData() {
        UUID owner = uuid();
        UUID alreadyHere = uuid();
        UUID fromLegacy = uuid();
        MaidRegistry live = populatedRegistry(owner, alreadyHere);

        // The renamed build has written something, so the old file must be ignored entirely.
        assertFalse(MaidRegistry.adoptIfEmpty(live, populatedRegistry(owner, fromLegacy)));
        assertTrue(live.isEnrolled(owner, alreadyHere));
        assertFalse("legacy data must not leak in", live.isEnrolled(owner, fromLegacy));
    }

    @Test
    public void registryAdoptionIsIdempotent() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidRegistry live = new MaidRegistry();
        MaidRegistry legacy = populatedRegistry(owner, maid);

        assertTrue(MaidRegistry.adoptIfEmpty(live, legacy));
        // Running again must be a no-op: the live store is no longer empty.
        assertFalse(MaidRegistry.adoptIfEmpty(live, legacy));
        assertEquals("no duplication", 1, live.enrolledMaids(owner).size());
    }

    @Test
    public void registryHandlesAMissingLegacyFile() {
        // The common case for a fresh world: there is nothing to adopt and that is not an error.
        assertFalse(MaidRegistry.adoptIfEmpty(new MaidRegistry(), null));
    }

    @Test
    public void registryIgnoresAnEmptyLegacyInstance() {
        // The new file was created, then the old one was found but holds nothing.
        assertFalse(MaidRegistry.adoptIfEmpty(new MaidRegistry(), new MaidRegistry()));
    }

    @Test
    public void registryAdoptionSurvivesSaveAndLoad() {
        // The end-to-end shape of a real migration: adopt, then persist and read back.
        UUID owner = uuid();
        UUID maid = uuid();
        MaidRegistry live = new MaidRegistry();
        MaidRegistry.adoptIfEmpty(live, populatedRegistry(owner, maid));

        MaidRegistry reloaded = MaidRegistry.load(live.save(new CompoundTag()));
        assertTrue(reloaded.isEnrolled(owner, maid));
    }

    @Test
    public void registryAdoptionCopiesForceLoadAndFavouritesToo() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidRegistry legacy = populatedRegistry(owner, maid);
        legacy.setForceLoad(owner, maid, true);
        legacy.setFavourite(owner, maid, true);

        MaidRegistry live = new MaidRegistry();
        MaidRegistry.adoptIfEmpty(live, legacy);

        assertTrue(live.isForceLoad(owner, maid));
        assertTrue(live.isFavourite(owner, maid));
    }

    // ------------------------------------------------------------------
    // MaidStorage
    // ------------------------------------------------------------------

    @Test
    public void storageAdoptsStoredMaidsAndDoesNotAliasTheirTags() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidStorage legacy = new MaidStorage();
        CompoundTag tag = new CompoundTag();
        tag.putString("CustomName", "Marisa");
        legacy.put(owner, maid, tag, "Marisa");

        MaidStorage live = new MaidStorage();
        assertTrue(MaidStorage.adoptIfEmpty(live, legacy));

        MaidStorage.StoredMaid record = live.get(owner, maid);
        assertTrue(record != null);
        assertEquals("Marisa", record.name());

        // Mutating the legacy tag must not change what the live store holds - otherwise a
        // later rewrite of the old file could silently corrupt a stored maid.
        tag.putString("CustomName", "changed");
        assertEquals("Marisa", record.data().getString("CustomName"));
    }

    @Test
    public void storageDoesNotAdoptOverExistingData() {
        UUID owner = uuid();
        UUID kept = uuid();
        MaidStorage live = new MaidStorage();
        live.put(owner, kept, new CompoundTag(), "Kept");

        MaidStorage legacy = new MaidStorage();
        legacy.put(owner, uuid(), new CompoundTag(), "Legacy");

        assertFalse(MaidStorage.adoptIfEmpty(live, legacy));
        assertTrue(live.contains(owner, kept));
        assertEquals("only the original maid remains", 1, live.list(owner).size());
    }

    @Test
    public void storageAdoptionIsIdempotentAndTolerantOfMissingLegacy() {
        MaidStorage live = new MaidStorage();
        assertFalse(MaidStorage.adoptIfEmpty(live, null));

        UUID owner = uuid();
        UUID maid = uuid();
        MaidStorage legacy = new MaidStorage();
        legacy.put(owner, maid, new CompoundTag(), "Maid");

        assertTrue(MaidStorage.adoptIfEmpty(live, legacy));
        assertFalse(MaidStorage.adoptIfEmpty(live, legacy));
        assertEquals(1, live.list(owner).size());
    }

    // ------------------------------------------------------------------
    // MaidDeathStorage
    // ------------------------------------------------------------------

    @Test
    public void deathStorageAdoptsCapturedDeathsIncludingTheirInventory() {
        UUID owner = uuid();
        UUID maid = uuid();
        CompoundTag data = new CompoundTag();
        data.putInt("MaidExperience", 120);

        MaidDeathStorage legacy = new MaidDeathStorage();
        legacy.recordDeath(owner, maid, data, "Marisa", true);

        MaidDeathStorage live = new MaidDeathStorage();
        assertTrue(MaidDeathStorage.adoptIfEmpty(live, legacy));

        MaidDeathStorage.DeadMaid record = live.get(owner, maid);
        assertTrue(record != null);
        assertTrue("her inventory snapshot must come across", record.withItems());
        assertEquals(120, record.data().getInt("MaidExperience"));
        assertEquals("the death streak must come across", 1, record.deathCount());

        // Same aliasing guarantee as the stored-maid case.
        data.putInt("MaidExperience", 999);
        assertEquals(120, record.data().getInt("MaidExperience"));
    }

    @Test
    public void deathStorageDoesNotAdoptOverExistingData() {
        UUID owner = uuid();
        UUID live1 = uuid();
        MaidDeathStorage live = new MaidDeathStorage();
        live.recordDeath(owner, live1, new CompoundTag(), "Live", true);

        MaidDeathStorage legacy = new MaidDeathStorage();
        legacy.recordDeath(owner, uuid(), new CompoundTag(), "Legacy", true);

        assertFalse(MaidDeathStorage.adoptIfEmpty(live, legacy));
        assertEquals(1, live.list(owner).size());
    }

    @Test
    public void deathStorageAdoptionIsIdempotentAndTolerantOfMissingLegacy() {
        MaidDeathStorage live = new MaidDeathStorage();
        assertFalse(MaidDeathStorage.adoptIfEmpty(live, null));

        UUID owner = uuid();
        UUID maid = uuid();
        MaidDeathStorage legacy = new MaidDeathStorage();
        legacy.recordDeath(owner, maid, new CompoundTag(), "Maid", false);

        assertTrue(MaidDeathStorage.adoptIfEmpty(live, legacy));
        assertFalse(MaidDeathStorage.adoptIfEmpty(live, legacy));
        assertEquals(1, live.list(owner).size());
    }

    // ------------------------------------------------------------------
    // MaidProgressStorage
    // ------------------------------------------------------------------

    @Test
    public void progressAdoptsLevelsAbilitiesAndTheBank() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidProgressStorage legacy = new MaidProgressStorage();
        legacy.setLevel(maid, MaidUpgrade.ATTACK, 37);
        legacy.setLevel(maid, MaidUpgrade.PICKUP, 4);
        legacy.setGlobalLevel(owner, com.dsh.maidmanager.logic.GlobalUpgrade.FLIGHT, 1);
        legacy.deposit(owner, 87.5F, 300.0F);

        MaidProgressStorage live = new MaidProgressStorage();
        assertTrue(MaidProgressStorage.adoptIfEmpty(live, legacy));

        assertEquals("upgrade levels must survive", 37, live.level(maid, MaidUpgrade.ATTACK));
        assertEquals(4, live.level(maid, MaidUpgrade.PICKUP));
        assertTrue("bought abilities must survive", live.hasGlobal(
                owner, com.dsh.maidmanager.logic.GlobalUpgrade.FLIGHT));
        assertEquals("the bank must survive", 87.5F, live.banked(owner), 1.0E-4F);
    }

    @Test
    public void progressDoesNotAdoptOverExistingData() {
        UUID owner = uuid();
        MaidProgressStorage live = new MaidProgressStorage();
        live.deposit(owner, 10.0F, 300.0F);

        MaidProgressStorage legacy = new MaidProgressStorage();
        legacy.deposit(owner, 999.0F, 300.0F);

        assertFalse(MaidProgressStorage.adoptIfEmpty(live, legacy));
        assertEquals("the live balance wins", 10.0F, live.banked(owner), 1.0E-4F);
    }

    @Test
    public void progressAdoptionIsIdempotentAndTolerantOfMissingLegacy() {
        MaidProgressStorage live = new MaidProgressStorage();
        assertFalse(MaidProgressStorage.adoptIfEmpty(live, null));

        UUID owner = uuid();
        UUID maid = uuid();
        MaidProgressStorage legacy = new MaidProgressStorage();
        legacy.setLevel(maid, MaidUpgrade.ARMOR, 9);

        assertTrue(MaidProgressStorage.adoptIfEmpty(live, legacy));
        assertFalse(MaidProgressStorage.adoptIfEmpty(live, legacy));
        assertEquals(9, live.level(maid, MaidUpgrade.ARMOR));
    }

    @Test
    public void progressAdoptionSurvivesSaveAndLoad() {
        UUID owner = uuid();
        UUID maid = uuid();
        MaidProgressStorage legacy = new MaidProgressStorage();
        legacy.setLevel(maid, MaidUpgrade.LUCK, 12);
        legacy.deposit(owner, 42.0F, 300.0F);

        MaidProgressStorage live = new MaidProgressStorage();
        MaidProgressStorage.adoptIfEmpty(live, legacy);

        MaidProgressStorage reloaded = MaidProgressStorage.load(live.save(new CompoundTag()));
        assertEquals(12, reloaded.level(maid, MaidUpgrade.LUCK));
        assertEquals(42.0F, reloaded.banked(owner), 1.0E-4F);
    }

    @Test
    public void anyExistingDataBlocksAdoptionEvenAnOptOutFlag() {
        // isEmpty() counts everything the store can hold, including a lone auto-deposit opt-out.
        // That is the conservative direction on purpose: the live store can only hold something
        // if the renamed build already wrote it, and then the live data must win. At first load
        // the store is created empty in the same call, so this never blocks a real migration.
        UUID owner = uuid();
        MaidProgressStorage live = new MaidProgressStorage();
        live.setAutoDepositEnabled(owner, false);

        MaidProgressStorage legacy = new MaidProgressStorage();
        legacy.deposit(owner, 50.0F, 300.0F);

        assertFalse("a store with any content must not be overwritten",
                MaidProgressStorage.adoptIfEmpty(live, legacy));
        assertEquals("the live balance stays untouched", 0.0F, live.banked(owner), 1.0E-4F);
    }

    @Test
    public void aFreshlyCreatedStoreDoesAdopt() {
        // The real first-load shape: computeIfAbsent has just created an empty store, so adoption
        // runs and nothing is lost.
        UUID owner = uuid();
        MaidProgressStorage legacy = new MaidProgressStorage();
        legacy.deposit(owner, 50.0F, 300.0F);

        MaidProgressStorage live = new MaidProgressStorage();
        assertTrue("a genuinely empty store must adopt",
                MaidProgressStorage.adoptIfEmpty(live, legacy));
        assertEquals(50.0F, live.banked(owner), 1.0E-4F);
    }
}
