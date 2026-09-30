package com.dsh.maidmanager.logic;

/**
 * The per-maid upgrades, bought with that maid's own experience.
 *
 * <p>This is the numeric half of the upgrade system; the ability half lives in
 * {@link GlobalUpgrade} and is bought with the player's banked P-points.
 *
 * <p><b>Why the per-level amounts look absurdly small.</b> These upgrades go to level 100, so the
 * per-level figure has to be a fraction: at +1 attack per level a maxed maid would swing for +100
 * on top of a base of 2. The UI therefore shows the <em>accumulated</em> total ("+1.85 attack"),
 * and the bar shows progress; the per-level number is never surfaced.
 *
 * <p>Deliberately free of Minecraft types. The attribute each id maps to lives in
 * {@link MaidUpgradeEffects}, so this enum can be unit tested without a registry.
 */
public enum MaidUpgrade {

    // --- combat tier -------------------------------------------------------------------
    /** +0.05 attack per level: +5.0 at 100. Base is 2, and favour level 3 adds +4 more. */
    ATTACK("attack", 0.05, false, Unit.FLAT, Tier.COMBAT),
    /** +0.4 max health per level: +40 at 100, against a base of 20. */
    HEALTH("health", 0.4, false, Unit.FLAT, Tier.COMBAT),
    /** +0.1 armour per level: +10 at 100. Vanilla armour caps near 30. */
    ARMOR("armor", 0.1, false, Unit.FLAT, Tier.COMBAT),
    /** +1% outgoing damage per level: +100% at 100. Applied by an event, not an attribute. */
    DAMAGE("damage", 0.01, true, Unit.PERCENT, Tier.COMBAT),
    /** +1% crossbow and gun attack speed per level: +100% at 100. */
    RANGED_SPEED("ranged_speed", 0.01, true, Unit.PERCENT, Tier.COMBAT),

    // --- utility tier ------------------------------------------------------------------
    /** +0.5% movement speed per level: +50% at 100. Not 500% - that is 5x and unplayable. */
    SPEED("speed", 0.005, true, Unit.PERCENT, Tier.UTILITY),
    /** +0.05 blocks of pickup range per level: +5 at 100. */
    PICKUP("pickup", 0.05, false, Unit.FLAT, Tier.UTILITY),
    /** +0.2 hunger per level: +20 at 100. */
    HUNGER("hunger", 0.2, false, Unit.FLAT, Tier.UTILITY),
    /** +0.2 luck per level: +20 at 100. Luck feeds mob drops. */
    LUCK("luck", 0.2, false, Unit.FLAT, Tier.UTILITY);

    /** How the UI renders the accumulated value. */
    public enum Unit {
        FLAT,
        PERCENT
    }

    /** Which cost curve applies. Combat upgrades are worth more, so they cost more. */
    public enum Tier {
        COMBAT,
        UTILITY
    }

    /** {@code {upToLevel, costPerLevel}} - the price steps, flat inside each band. */
    private static final int[][] COMBAT_TIERS = {
            {20, 20}, {40, 120}, {60, 600}, {80, 2500}, {100, 6000}
    };
    private static final int[][] UTILITY_TIERS = {
            {20, 10}, {40, 70}, {60, 400}, {80, 1500}, {100, 4000}
    };

    public static final int MAX_LEVEL = 100;

    private final String id;
    private final double perLevel;
    private final boolean percent;
    private final Unit unit;
    private final Tier tier;

    MaidUpgrade(String id, double perLevel, boolean percent, Unit unit, Tier tier) {
        this.id = id;
        this.perLevel = perLevel;
        this.percent = percent;
        this.unit = unit;
        this.tier = tier;
    }

    /** Stable id used in NBT and on the wire. Never change an existing one. */
    public String id() {
        return id;
    }

    public String translationKey() {
        return "gui.touhou_maid_legion.upgrade." + id;
    }

    public String descriptionKey() {
        return "gui.touhou_maid_legion.upgrade." + id + ".desc";
    }

    public int maxLevel() {
        return MAX_LEVEL;
    }

    public Unit unit() {
        return unit;
    }

    public Tier tier() {
        return tier;
    }

    /** The value added to the attribute per level, in that attribute's own units. */
    public double perLevel() {
        return perLevel;
    }

    /** The accumulated bonus at {@code level}, in the attribute's own units. */
    public double amountAt(int level) {
        return perLevel * clampLevel(level);
    }

    /**
     * The number the player should see, as text.
     *
     * <p>Percentages are stored as fractions for the attribute API but shown as whole percents,
     * which is why this is not simply {@link #amountAt}.
     */
    public String displayAt(int level) {
        double value = amountAt(level);
        if (unit == Unit.PERCENT) {
            return trim(value * 100.0) + "%";
        }
        return trim(value);
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    /** Cost of buying the next level, or 0 when already maxed. */
    public int costFor(int currentLevel) {
        if (currentLevel >= MAX_LEVEL) {
            return 0;
        }
        for (int[] band : (tier == Tier.COMBAT ? COMBAT_TIERS : UTILITY_TIERS)) {
            if (currentLevel < band[0]) {
                return band[1];
            }
        }
        return 0;
    }

    /** Total experience sunk to reach {@code level} from scratch, for the panel header. */
    public int totalSpentAt(int level) {
        int target = clampLevel(level);
        int total = 0;
        int done = 0;
        for (int[] band : (tier == Tier.COMBAT ? COMBAT_TIERS : UTILITY_TIERS)) {
            int take = Math.min(target, band[0]) - done;
            if (take <= 0) {
                break;
            }
            total += take * band[1];
            done = band[0];
        }
        return total;
    }

    public static int clampLevel(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /** Lookup by stored id; null when the save carries an upgrade we no longer know. */
    public static MaidUpgrade byId(String id) {
        if (id == null) {
            return null;
        }
        for (MaidUpgrade upgrade : values()) {
            if (upgrade.id.equals(id)) {
                return upgrade;
            }
        }
        return null;
    }
}
