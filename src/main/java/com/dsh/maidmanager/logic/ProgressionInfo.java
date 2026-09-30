package com.dsh.maidmanager.logic;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The player-wide half of a snapshot: wallet, bank, owned abilities and the shrines on hand.
 *
 * <p>Travels with the maid list rather than in its own message because the screens need a
 * consistent view - showing a maid's row from one tick and the bank balance from another would
 * let the player see money they have already spent.
 *
 * @param wallet      TLM's own P-point balance, hard-capped at 5.0 by TLM itself
 * @param bank        points held in the Legion bank
 * @param bankCap     configured bank capacity
 * @param autoDeposit whether the automatic wallet sweep is on for this player
 * @param globalLevels ability state per {@link GlobalUpgrade#ordinal()}; see the constants below
 * @param shrineCount shrines in the player's inventory, so the panel can show affordability
 */
public record ProgressionInfo(float wallet, float bank, float bankCap, boolean autoDeposit,
                              int[] globalLevels, int shrineCount, boolean lampBound) {

    /**
     * Ability states, packed into {@code globalLevels}.
     *
     * <p>Owning an ability and having it switched on are separate things, and the wire format
     * already carries one int per ability, so the switch rides along in the same slot rather than
     * needing a parallel array.
     */
    public static final int NOT_OWNED = 0;
    public static final int OWNED_ACTIVE = 1;
    public static final int OWNED_DISABLED = 2;

    public static ProgressionInfo empty() {
        return new ProgressionInfo(0.0F, 0.0F, 0.0F, true,
                new int[GlobalUpgrade.values().length], 0, false);
    }

    private int state(GlobalUpgrade ability) {
        int index = ability.ordinal();
        return index < globalLevels.length ? globalLevels[index] : NOT_OWNED;
    }

    /** Whether the player has bought the ability, regardless of its switch. */
    public boolean has(GlobalUpgrade ability) {
        return state(ability) != NOT_OWNED;
    }

    /**
     * Whether the ability is currently doing anything.
     *
     * <p>False both when it was never bought and when it was switched off; callers that need to
     * tell those apart should ask {@link #has(GlobalUpgrade)} as well.
     */
    public boolean isEnabled(GlobalUpgrade ability) {
        return state(ability) == OWNED_ACTIVE;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(wallet);
        buf.writeFloat(bank);
        buf.writeFloat(bankCap);
        buf.writeBoolean(autoDeposit);
        buf.writeVarIntArray(globalLevels);
        buf.writeVarInt(shrineCount);
        buf.writeBoolean(lampBound);
    }

    public static ProgressionInfo read(FriendlyByteBuf buf) {
        float wallet = buf.readFloat();
        float bank = buf.readFloat();
        float bankCap = buf.readFloat();
        boolean auto = buf.readBoolean();
        int[] levels = buf.readVarIntArray();
        int[] normalised = new int[GlobalUpgrade.values().length];
        System.arraycopy(levels, 0, normalised, 0, Math.min(levels.length, normalised.length));
        int shrines = Math.max(0, buf.readVarInt());
        boolean lamp = buf.readBoolean();
        return new ProgressionInfo(wallet, bank, bankCap, auto, normalised, shrines, lamp);
    }
}
