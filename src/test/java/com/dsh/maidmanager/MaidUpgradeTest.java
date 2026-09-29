package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.GlobalUpgrade;
import com.dsh.maidmanager.logic.MaidUpgrade;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the upgrade tables and their cost curves.
 *
 * <p>These are pure data, so they are testable without a running game. That matters more than
 * usual here: the numbers are the balance, and a typo in a tier boundary silently changes what
 * the whole progression costs. The total-at-max assertions are the guard - if someone edits a
 * band, the sum moves and the test says so.
 */
public class MaidUpgradeTest {

    @Test
    public void costsExistForEveryLevelUpToTheCap() {
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            assertEquals("all upgrades go to 100", 100, upgrade.maxLevel());
            // A cost must exist right up to the last level, and stop exactly at the cap.
            for (int level = 0; level < upgrade.maxLevel(); level++) {
                assertTrue(upgrade.id() + " level " + level + " must cost something",
                        upgrade.costFor(level) > 0);
            }
            assertEquals(upgrade.id() + " must be free at max", 0, upgrade.costFor(100));
        }
    }

    @Test
    public void costNeverDecreasesAsLevelsRise() {
        // The panel shows the price of the next level; a dip would read as a bug to the player.
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            int previous = 0;
            for (int level = 0; level < upgrade.maxLevel(); level++) {
                int cost = upgrade.costFor(level);
                assertTrue(upgrade.id() + " cost dropped at level " + level,
                        cost >= previous);
                previous = cost;
            }
        }
    }

    @Test
    public void combatTierCostsMoreThanUtilityTier() {
        for (int level = 0; level < 100; level++) {
            int combat = MaidUpgrade.ATTACK.costFor(level);
            int utility = MaidUpgrade.LUCK.costFor(level);
            assertTrue("combat should never be cheaper at level " + level, combat >= utility);
        }
        assertTrue("combat must actually be dearer overall",
                MaidUpgrade.ATTACK.totalSpentAt(100) > MaidUpgrade.LUCK.totalSpentAt(100));
    }

    @Test
    public void totalSpentMatchesTheSumOfBands() {
        // Attack: 20 levels at 20, then 120, 600, 2500, 6000.
        int expected = 20 * 20 + 20 * 120 + 20 * 600 + 20 * 2500 + 20 * 6000;
        assertEquals(184_800, expected);
        assertEquals(expected, MaidUpgrade.ATTACK.totalSpentAt(100));

        // Luck is a utility upgrade, so it runs on the cheaper curve.
        int utility = 20 * 10 + 20 * 70 + 20 * 400 + 20 * 1500 + 20 * 4000;
        assertEquals(119_600, utility);
        assertEquals(utility, MaidUpgrade.LUCK.totalSpentAt(100));
    }

    @Test
    public void totalSpentIsPartialForAPartialLevel() {
        assertEquals(0, MaidUpgrade.ATTACK.totalSpentAt(0));
        assertEquals(20, MaidUpgrade.ATTACK.totalSpentAt(1));
        assertEquals(20 * 20, MaidUpgrade.ATTACK.totalSpentAt(20));
        // One level into the second band.
        assertEquals(20 * 20 + 120, MaidUpgrade.ATTACK.totalSpentAt(21));
    }

    @Test
    public void accumulatedAmountIsLinearInLevelAndCapped() {
        assertEquals(0.0, MaidUpgrade.ATTACK.amountAt(0), 1.0E-9);
        assertEquals(0.05, MaidUpgrade.ATTACK.amountAt(1), 1.0E-9);
        assertEquals(5.0, MaidUpgrade.ATTACK.amountAt(100), 1.0E-9);
        // Out-of-range levels clamp rather than extrapolating past the cap.
        assertEquals(5.0, MaidUpgrade.ATTACK.amountAt(500), 1.0E-9);
        assertEquals(0.0, MaidUpgrade.ATTACK.amountAt(-3), 1.0E-9);
    }

    @Test
    public void maxedTotalsMatchThePublishedBalanceTable() {
        assertEquals(5.0, MaidUpgrade.ATTACK.amountAt(100), 1.0E-9);
        assertEquals(40.0, MaidUpgrade.HEALTH.amountAt(100), 1.0E-9);
        assertEquals(10.0, MaidUpgrade.ARMOR.amountAt(100), 1.0E-9);
        assertEquals(5.0, MaidUpgrade.PICKUP.amountAt(100), 1.0E-9);
        assertEquals(20.0, MaidUpgrade.HUNGER.amountAt(100), 1.0E-9);
        assertEquals(20.0, MaidUpgrade.LUCK.amountAt(100), 1.0E-9);
        // Percentages are stored as fractions for the attribute API; 0.5 means +50%.
        assertEquals(0.50, MaidUpgrade.SPEED.amountAt(100), 1.0E-9);
        assertEquals(1.00, MaidUpgrade.RANGED_SPEED.amountAt(100), 1.0E-9);
        assertEquals(1.00, MaidUpgrade.DAMAGE.amountAt(100), 1.0E-9);
    }

    @Test
    public void percentagesAreDisplayedInWholePercent() {
        // The stored fraction would show "0.5" and read as nonsense; the panel must say "50%".
        // The sign is added by the screen, so displayAt returns the bare value.
        assertEquals("50%", MaidUpgrade.SPEED.displayAt(100));
        assertEquals("100%", MaidUpgrade.DAMAGE.displayAt(100));
        assertEquals("1%", MaidUpgrade.DAMAGE.displayAt(1));
        // Flat upgrades show the raw accumulated value, with no trailing zeroes.
        assertEquals("5", MaidUpgrade.ATTACK.displayAt(100));
        assertEquals("1.85", MaidUpgrade.ATTACK.displayAt(37));
        assertEquals("0.6", MaidUpgrade.HUNGER.displayAt(3));
    }

    @Test
    public void idsAreUniqueAndParseBothWays() {
        Set<String> seen = new HashSet<>();
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            assertTrue("duplicate id " + upgrade.id(), seen.add(upgrade.id()));
            assertEquals(upgrade, MaidUpgrade.byId(upgrade.id()));
            assertNotNull(upgrade.translationKey());
            assertNotNull(upgrade.descriptionKey());
        }
    }

    @Test
    public void unknownOrNullIdResolvesToNull() {
        // A save written by a newer build, or one whose upgrade was removed, must load quietly.
        assertNull(MaidUpgrade.byId("no_such_upgrade"));
        assertNull(MaidUpgrade.byId(null));
    }

    @Test
    public void bandsAreOrderedAndCoverTheWholeRange() {
        // A gap between bands would make costFor return 0 mid-progression, i.e. a free level.
        int[] boundaries = {20, 40, 60, 80, 100};
        for (int i = 0; i < boundaries.length; i++) {
            int expected = switch (i) {
                case 0 -> 20;
                case 1 -> 120;
                case 2 -> 600;
                case 3 -> 2500;
                default -> 6000;
            };
            assertEquals(expected, MaidUpgrade.ATTACK.costFor(boundaries[i] - 1));
        }
    }

    @Test
    public void theTwoUpgradeTablesDoNotShareIds() {
        // Both are stored by id in the same save, so a collision would cross-wire them.
        Set<String> ids = new HashSet<>();
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            assertTrue(upgrade.id(), ids.add(upgrade.id()));
        }
        for (GlobalUpgrade ability : GlobalUpgrade.values()) {
            assertTrue(ability.id(), ids.add(ability.id()));
        }
        // And each table must reject the other's ids.
        assertNull(MaidUpgrade.byId(GlobalUpgrade.FLIGHT.id()));
        assertNull(GlobalUpgrade.byId(MaidUpgrade.ATTACK.id()));
    }

    @Test
    public void onlyTheDamageUpgradeIsEventDriven() {
        // Damage is applied through LivingHurtEvent rather than an attribute; if a future edit
        // gave it an attribute this would be the reminder that a modifier is missing.
        Set<MaidUpgrade> eventDriven = new HashSet<>();
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            if (upgrade == MaidUpgrade.DAMAGE) {
                eventDriven.add(upgrade);
            }
        }
        assertEquals(Set.of(MaidUpgrade.DAMAGE), eventDriven);
    }

    @Test
    public void percentUpgradesUseMultiplyBaseAndFlatsUseAddition() {
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            boolean byPercent = upgrade.unit() == MaidUpgrade.Unit.PERCENT;
            if (byPercent) {
                // Stored as a small fraction, because MULTIPLY_BASE multiplies the base value.
                assertTrue(upgrade.id() + " should be a fraction", upgrade.perLevel() < 0.1);
            } else {
                assertTrue(upgrade.id() + " should be a real amount", upgrade.perLevel() >= 0.05);
            }
        }
        assertFalse(MaidUpgrade.ATTACK.unit() == MaidUpgrade.Unit.PERCENT);
    }
}
