package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.GlobalUpgrade;
import com.dsh.maidmanager.logic.MaidProgressStorage;
import com.dsh.maidmanager.logic.MaidUpgrade;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the persistence and clamping rules of the progression state.
 *
 * <p>{@link MaidProgressStorage} is exercised directly rather than through
 * {@code MaidProgressionService}, which imports {@code EntityMaid} and cannot be loaded outside a
 * real FML runtime. The storage class touches only NBT types, so it works as a plain object - the
 * same approach the death-storage tests use.
 *
 * <p>1.21 added a {@code HolderLookup.Provider} to {@code SavedData.load/save}. This data is plain
 * NBT, so the provider is unused - but the signatures require one.
 *
 * <p>The clamping tests matter most. Every number here reaches a player's balance or their
 * upgrade levels, so an off-by-one or a missing bound is a dupe or a wipe, not a cosmetic bug.
 */
public class MaidProgressStorageTest {

    private static final net.minecraft.core.HolderLookup.Provider PROVIDER =
            net.minecraft.core.RegistryAccess.EMPTY;

    private static UUID uuid() {
        return UUID.randomUUID();
    }

    /** Round-trips through save/load, the way a server restart would. */
    private static MaidProgressStorage reload(MaidProgressStorage storage) {
        return MaidProgressStorage.load(storage.save(new CompoundTag(), PROVIDER), PROVIDER);
    }

    @Test
    public void maidLevelsSurviveSaveAndLoad() {
        UUID maid = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        storage.setLevel(maid, MaidUpgrade.ATTACK, 37);
        storage.setLevel(maid, MaidUpgrade.PICKUP, 4);

        MaidProgressStorage loaded = reload(storage);
        assertEquals(37, loaded.level(maid, MaidUpgrade.ATTACK));
        assertEquals(4, loaded.level(maid, MaidUpgrade.PICKUP));
        // An upgrade that was never bought reads as zero rather than throwing.
        assertEquals(0, loaded.level(maid, MaidUpgrade.LUCK));
        assertEquals(0, loaded.level(uuid(), MaidUpgrade.ATTACK));
    }

    @Test
    public void globalLevelsSurviveSaveAndLoadAndStayPerOwner() {
        UUID alice = uuid();
        UUID bob = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        storage.setGlobalLevel(alice, GlobalUpgrade.FLIGHT, 1);

        MaidProgressStorage loaded = reload(storage);
        assertTrue(loaded.hasGlobal(alice, GlobalUpgrade.FLIGHT));
        // Abilities are bought per player; Bob must not inherit Alice's purchase.
        assertFalse(loaded.hasGlobal(bob, GlobalUpgrade.FLIGHT));
        assertFalse(loaded.hasGlobal(alice, GlobalUpgrade.EXP_BONUS));
    }

    @Test
    public void abilitiesAreCappedAtOneLevel() {
        UUID owner = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        // Abilities are one-off purchases; asking for more must not bank extra levels.
        storage.setGlobalLevel(owner, GlobalUpgrade.EXP_BONUS, 7);
        assertEquals(1, storage.globalLevel(owner, GlobalUpgrade.EXP_BONUS));
    }

    @Test
    public void maidLevelsAreClampedToTheUpgradeCap() {
        UUID maid = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        storage.setLevel(maid, MaidUpgrade.ATTACK, 10_000);
        assertEquals(MaidUpgrade.MAX_LEVEL, storage.level(maid, MaidUpgrade.ATTACK));
        // Negative input is treated as zero, and a zero level removes the entry entirely rather
        // than leaving a "0" behind for every upgrade the player ever looked at.
        storage.setLevel(maid, MaidUpgrade.HEALTH, -5);
        assertEquals(0, storage.level(maid, MaidUpgrade.HEALTH));
        assertFalse(storage.levelsOf(maid).containsKey(MaidUpgrade.HEALTH.id()));
        assertEquals("only the maxed attack entry should remain", 1, storage.levelsOf(maid).size());
    }

    @Test
    public void bankedPowerSurvivesSaveAndLoad() {
        UUID owner = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        storage.deposit(owner, 40.0F, 300.0F);
        assertEquals(40.0F, reload(storage).banked(owner), 1.0E-4F);
    }

    @Test
    public void depositIsClampedToTheRemainingRoom() {
        UUID owner = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        assertEquals(90.0F, storage.deposit(owner, 100.0F, 90.0F), 1.0E-4F);
        assertEquals(90.0F, storage.banked(owner), 1.0E-4F);
        // Already full: nothing moves, and the balance does not creep past the cap.
        assertEquals(0.0F, storage.deposit(owner, 50.0F, 90.0F), 1.0E-4F);
        assertEquals(90.0F, storage.banked(owner), 1.0E-4F);
    }

    @Test
    public void depositRejectsNonPositiveAmountsAndCaps() {
        UUID owner = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        assertEquals(0.0F, storage.deposit(owner, 0.0F, 100.0F), 1.0E-4F);
        assertEquals(0.0F, storage.deposit(owner, -5.0F, 100.0F), 1.0E-4F);
        assertEquals(0.0F, storage.deposit(owner, 5.0F, 0.0F), 1.0E-4F);
        assertEquals(0.0F, storage.banked(owner), 1.0E-4F);
    }

    @Test
    public void withdrawIsClampedToTheBalanceAndEmptiesCleanly() {
        UUID owner = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        storage.deposit(owner, 40.0F, 300.0F);
        assertEquals(10.0F, storage.withdraw(owner, 10.0F), 1.0E-4F);
        assertEquals(30.0F, storage.banked(owner), 1.0E-4F);
        // Asking for more than exists returns only what exists.
        assertEquals(30.0F, storage.withdraw(owner, 999.0F), 1.0E-4F);
        assertEquals(0.0F, storage.banked(owner), 1.0E-4F);
        // From empty, still zero, and no negative balance appears.
        assertEquals(0.0F, storage.withdraw(owner, 5.0F), 1.0E-4F);
    }

    @Test
    public void chargeFailsWhenShortAndSucceedsExactlyAtTheBalance() {
        UUID owner = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        storage.deposit(owner, 100.0F, 300.0F);

        assertFalse("must not go into debt", storage.charge(owner, 101));
        assertEquals("a refused charge must not move the balance",
                100.0F, storage.banked(owner), 1.0E-4F);

        // Float storage means an exact-boundary charge has to be tolerated, not rejected.
        assertTrue(storage.charge(owner, 100));
        assertEquals(0.0F, storage.banked(owner), 1.0E-4F);
    }

    @Test
    public void autoDepositDefaultsToOnAndTheOptOutPersists() {
        UUID owner = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        assertTrue("on by default, so points bank without being asked",
                storage.autoDepositEnabled(owner));

        storage.setAutoDepositEnabled(owner, false);
        assertFalse(storage.autoDepositEnabled(owner));
        assertFalse(reload(storage).autoDepositEnabled(owner));
        // A different player is unaffected by one player's choice.
        assertTrue(reload(storage).autoDepositEnabled(uuid()));

        storage.setAutoDepositEnabled(owner, true);
        assertTrue("turning it back on must clear the opt-out",
                reload(storage).autoDepositEnabled(owner));
    }

    @Test
    public void malformedOwnerKeysAreSkippedRatherThanFatal() {
        // A hand-edited or corrupted save must not take the whole roster down with it.
        UUID valid = uuid();
        CompoundTag tag = new CompoundTag();
        CompoundTag bank = new CompoundTag();
        bank.putFloat("not-a-uuid", 12.0F);
        bank.putFloat(valid.toString(), 8.0F);
        tag.put("BankedPower", bank);

        MaidProgressStorage loaded = MaidProgressStorage.load(tag, PROVIDER);
        // The garbage entry is dropped, and the valid one still loads at its real value - a
        // loader that bailed out on the first bad key would have lost this balance.
        assertEquals(8.0F, loaded.banked(valid), 1.0E-4F);
    }

    @Test
    public void unknownUpgradeIdsAreDroppedInsteadOfThrowing() {
        // Forward compatibility: a save from a build with an upgrade this build lacks.
        UUID maid = uuid();
        CompoundTag tag = new CompoundTag();
        CompoundTag maidLevels = new CompoundTag();
        CompoundTag levels = new CompoundTag();
        levels.putInt("some_removed_upgrade", 9);
        levels.putInt(MaidUpgrade.ARMOR.id(), 3);
        maidLevels.put(maid.toString(), levels);
        tag.put("MaidLevels", maidLevels);

        MaidProgressStorage loaded = MaidProgressStorage.load(tag, PROVIDER);
        assertEquals(3, loaded.level(maid, MaidUpgrade.ARMOR));
        assertEquals(1, loaded.levelsOf(maid).size());
    }

    @Test
    public void zeroLevelsAreNotPersistedAtAll() {
        // Keeps the save from growing a key per maid per upgrade that was never touched.
        UUID maid = uuid();
        MaidProgressStorage storage = new MaidProgressStorage();
        storage.setLevel(maid, MaidUpgrade.ATTACK, 5);
        storage.setLevel(maid, MaidUpgrade.ATTACK, 0);

        MaidProgressStorage loaded = reload(storage);
        assertTrue(loaded.levelsOf(maid).isEmpty());
        assertEquals(0, loaded.level(maid, MaidUpgrade.ATTACK));
    }
}