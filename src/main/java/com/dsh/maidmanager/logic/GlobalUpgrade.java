package com.dsh.maidmanager.logic;

/**
 * The legion-wide abilities, bought once each with the player's banked P-points.
 *
 * <p><b>Why these are not just bigger numbers.</b> Buying stats with P-points would make this
 * screen a duplicate of the per-maid one, so every entry here does something a per-maid upgrade
 * cannot: it changes a rule, for every enrolled maid at once. Each is a single purchase with no
 * levels.
 *
 * <p>Free of Minecraft types, so it can be unit tested without a registry.
 */
public enum GlobalUpgrade {

    /**
     * Doubles the experience every enrolled maid gains, from orbs and from P-point pickups.
     * Applied by {@link MaidUpgradeHandler} through {@code MaidPickupEvent}.
     */
    EXP_BONUS("g_exp", 80, Kind.EVENT),

    /**
     * Removes TLM's death penalty outright (native behaviour is -2 favour).
     *
     * <p>Worth noting this exists because of a quirk: our death snapshot is taken at
     * {@code LivingDeathEvent} with priority HIGHEST, while TLM applies the penalty from its own
     * listener at the default priority. The snapshot therefore predates the penalty and would
     * silently erase it on revive. {@link MaidProgressionService} re-applies the penalty on
     * revive, and this ability is what removes it.
     */
    DEATH_FAVOR("g_favor", 100, Kind.EVENT),

    /**
     * While the owner is in creative flight, enrolled maids follow through the air instead of
     * running along the ground. Driven by {@link MaidFlightHandler}.
     */
    FLIGHT("g_flight", 120, Kind.BEHAVIOR),

    /**
     * Switches reviving onto the shrine route: three shrines and a channelled cast, no materials.
     *
     * <p>Appended last on purpose. Ability state travels as an array indexed by
     * {@link #ordinal()}, so inserting anywhere earlier would silently re-point every existing
     * save's abilities at the wrong entry.
     */
    SHRINE_REVIVE("g_shrine", 0, 3, Kind.EVENT);

    /** How the ability takes effect, so the service knows what to wire up. */
    public enum Kind {
        /** Read at event time. */
        EVENT,
        /** Needs a per-tick driver. */
        BEHAVIOR
    }

    private final String id;
    private final int powerCost;
    /** Shrines needed to unlock instead of points; see {@link #shrineCost()}. */
    private final int shrineCost;
    private final Kind kind;

    GlobalUpgrade(String id, int powerCost, Kind kind) {
        this(id, powerCost, 0, kind);
    }

    GlobalUpgrade(String id, int powerCost, int shrineCost, Kind kind) {
        this.shrineCost = shrineCost;
        this.id = id;
        this.powerCost = powerCost;
        this.kind = kind;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "gui.touhou_maid_legion.global." + id;
    }

    public String descriptionKey() {
        return "gui.touhou_maid_legion.global." + id + ".desc";
    }

    /** Abilities are bought outright; there is no level curve. */
    public int maxLevel() {
        return 1;
    }

    /** Cost in banked P-points. */
    public int powerCost() {
        return powerCost;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Whether the player may switch this ability off after buying it.
     *
     * <p>Only the flight ability qualifies, because it is the one that takes over a maid's
     * movement: a player who finds it fighting their own mods needs to be able to park it without
     * losing the purchase. The other two only change numbers, so a switch would be clutter.
     *
     * <p>{@link MaidProgressStorage} stores the switch for every ability regardless, so widening
     * this later is a one-line change.
     */
    /**
     * Shrines needed to unlock this ability, instead of P points.
     *
     * <p>Zero for every ability bought from the bank. The shrine revival is the exception: it is
     * paid for in the shrines themselves, once, and is free forever after.
     */
    public int shrineCost() {
        return shrineCost;
    }

    /** True when this is unlocked with shrines rather than banked P points. */
    public boolean buyableWithShrines() {
        return shrineCost > 0;
    }

    public boolean toggleable() {
        return this == FLIGHT || this == SHRINE_REVIVE;
    }

    public static GlobalUpgrade byId(String id) {
        if (id == null) {
            return null;
        }
        for (GlobalUpgrade upgrade : values()) {
            if (upgrade.id.equals(id)) {
                return upgrade;
            }
        }
        return null;
    }
}
