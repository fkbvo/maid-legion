package com.dsh.maidmanager;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for the maid terminal.
 *
 * <p>Deliberately small: the per-maid "force load" switch is stored per maid (see
 * {@link com.dsh.maidmanager.logic.MaidRegistry}) rather than here, because the user asked
 * for per-maid control.
 */
public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    private Config() {
    }

    public static final class Common {
        public final ForgeConfigSpec.IntValue maxSummonPerAction;
        public final ForgeConfigSpec.IntValue summonIntervalTicks;
        public final ForgeConfigSpec.BooleanValue allowForceLoad;
        public final ForgeConfigSpec.IntValue forceLoadTimeoutTicks;
        public final ForgeConfigSpec.BooleanValue enableUpgrades;
        public final ForgeConfigSpec.DoubleValue maxBankedPower;
        public final ForgeConfigSpec.DoubleValue autoDepositThreshold;
        public final ForgeConfigSpec.BooleanValue enableFlightFollow;
        public final ForgeConfigSpec.IntValue flightReleaseGraceTicks;
        public final ForgeConfigSpec.BooleanValue autoDrainBeacon;
        public final ForgeConfigSpec.DoubleValue beaconReserve;
        public final ForgeConfigSpec.DoubleValue powerPerItem;

        Common(ForgeConfigSpec.Builder builder) {
            builder.comment("Maid Legion settings").push("general");

            maxSummonPerAction = builder
                    .comment("Maximum number of maids handled by a single summon/store action.",
                            "Remaining maids are queued and processed over the following ticks.")
                    .defineInRange("maxSummonPerAction", 12, 1, 128);

            summonIntervalTicks = builder
                    .comment("Ticks between two maids when processing a multi-maid action.",
                            "Higher values are gentler on the server.")
                    .defineInRange("summonIntervalTicks", 2, 1, 200);

            allowForceLoad = builder
                    .comment("Allow the per-maid 'force load' switch to temporarily load chunks.",
                            "Server owners can turn this off to forbid any chunk loading at all.",
                            "When false the switch is shown but disabled in the GUI.")
                    .define("allowForceLoad", true);

            forceLoadTimeoutTicks = builder
                    .comment("How long a temporary chunk ticket may live while waiting for a maid",
                            "to become available. Tickets are always released afterwards.")
                    .defineInRange("forceLoadTimeoutTicks", 100, 20, 1200);

            builder.pop();

            builder.comment("Upgrades bought with maid experience, and legion abilities bought",
                            "with banked P-points.").push("upgrades");

            enableUpgrades = builder
                    .comment("Master switch for the upgrade and P-point bank screens.",
                            "When false the buttons are shown but disabled.")
                    .define("enableUpgrades", true);

            maxBankedPower = builder
                    .comment("Capacity of the Legion's P-point bank.",
                            "TLM's own wallet is hard-capped at 5.0 and cannot be raised, so the",
                            "bank is what actually gives P-points somewhere to accumulate.")
                    .defineInRange("maxBankedPower", 300.0, 5.0, 100000.0);

            autoDepositThreshold = builder
                    .comment("Sweep the player's TLM wallet into the bank whenever it exceeds this.",
                            "Keeps pickup room free so P-points bank instead of overflowing into",
                            "vanilla experience. 0 drains the wallet completely;",
                            "5.0 or above effectively disables the sweep.",
                            "Players can also switch auto-deposit off per player in the panel.")
                    .defineInRange("autoDepositThreshold", 1.0, 0.0, 5.0);

            enableFlightFollow = builder
                    .comment("Allow the flight-follow ability: while the owner is in creative",
                            "flight, enrolled maids follow through the air.",
                            "Turn off if it fights another mod's movement handling.")
                    .define("enableFlightFollow", true);

            flightReleaseGraceTicks = builder
                    .comment("How long a flying maid keeps hovering after flight-follow stops",
                            "applying, so switching the ability off does not drop her from the sky",
                            "and hurt her. 0 releases gravity immediately.")
                    .defineInRange("flightReleaseGraceTicks", 40, 0, 200);

            builder.pop();

            builder.comment("Shrine lamps and P-point items").push("power");

            autoDrainBeacon = builder
                    .comment("Automatically move stored P-points out of the shrine lamp bound",
                            "with a gohei and into the bank. The lamp absorbs nearby P-points",
                            "losslessly and holds far more than TLM's 5.0 wallet, so this is the",
                            "fast route into the bank.")
                    .define("autoDrainBeacon", true);

            beaconReserve = builder
                    .comment("Points left in a bound lamp so its own buff is not starved.",
                            "The effective reserve is the larger of this and the lamp's own",
                            "per-effect cost. Set 0 to drain it completely.")
                    .defineInRange("beaconReserve", 0.9, 0.0, 100.0);

            powerPerItem = builder
                    .comment("P-points credited for one P-point item deposited straight into the",
                            "bank. Defaults to the smallest of TLM's pickup tiers so a mistake",
                            "under-credits rather than conjuring points.")
                    .defineInRange("powerPerItem", 2.85, 0.01, 100.0);

            builder.pop();
        }
    }
}
