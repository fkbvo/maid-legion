package com.dsh.maidmanager;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for the maid terminal.
 *
 * <p>Deliberately small: the per-maid "force load" switch is stored per maid (see
 * {@link com.dsh.maidmanager.logic.MaidRegistry}) rather than here, because the user asked
 * for per-maid control.
 *
 * <p>The only 1.21 change is the class name: NeoForge moved the builder from
 * {@code ForgeConfigSpec} to {@code net.neoforged.neoforge.common.ModConfigSpec}. The
 * builder API itself is unchanged.
 */
public final class Config {
    public static final ModConfigSpec SPEC;
    public static final Common COMMON;

    static {
        // NeoForge's ModConfigSpec.Builder.configure still returns the commons-lang3 Pair
        // (getLeft/getSecond) exactly as Forge's ForgeConfigSpec did - only the builder class
        // itself was renamed. Do not switch this to a Mojang datafixer Pair.
        org.apache.commons.lang3.tuple.Pair<Common, ModConfigSpec> pair =
                new ModConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    private Config() {
    }

    public static final class Common {
        public final ModConfigSpec.IntValue maxSummonPerAction;
        public final ModConfigSpec.IntValue summonIntervalTicks;
        public final ModConfigSpec.BooleanValue allowForceLoad;
        public final ModConfigSpec.IntValue forceLoadTimeoutTicks;
        public final ModConfigSpec.BooleanValue enableUpgrades;
        public final ModConfigSpec.DoubleValue maxBankedPower;
        public final ModConfigSpec.DoubleValue autoDepositThreshold;
        public final ModConfigSpec.BooleanValue enableFlightFollow;

        Common(ModConfigSpec.Builder builder) {
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
                            "TLM's own wallet is hard-capped and cannot be raised, so the",
                            "bank is what actually gives P-points somewhere to accumulate.")
                    .defineInRange("maxBankedPower", 300.0, 5.0, 100000.0);

            autoDepositThreshold = builder
                    .comment("Sweep the player's TLM wallet into the bank whenever it exceeds this.",
                            "Keeps pickup room free so P-points bank instead of overflowing into",
                            "vanilla experience. 0 drains the wallet completely;",
                            "a value at or above the wallet cap effectively disables the sweep.",
                            "Players can also switch auto-deposit off per player in the panel.")
                    .defineInRange("autoDepositThreshold", 1.0, 0.0, 5.0);

            enableFlightFollow = builder
                    .comment("Allow the flight-follow ability: while the owner is in creative",
                            "flight, enrolled maids follow through the air.",
                            "Turn off if it fights another mod's movement handling.")
                    .define("enableFlightFollow", true);

            builder.pop();
        }
    }
}
