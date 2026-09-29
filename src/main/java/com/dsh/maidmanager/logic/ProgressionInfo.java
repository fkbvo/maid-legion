package com.dsh.maidmanager.logic;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The player-wide half of a snapshot: wallet, bank and which legion abilities are owned.
 *
 * <p>Travels with the maid list rather than in its own message because the screens need a
 * consistent view - showing a maid's row from one tick and the bank balance from another would
 * let the player see money they have already spent.
 *
 * @param wallet      TLM's own P-point balance, hard-capped at 5.0 by TLM itself
 * @param bank        points held in the Legion bank
 * @param bankCap     configured bank capacity
 * @param autoDeposit whether the automatic wallet sweep is on for this player
 * @param globalLevels owned abilities indexed by {@link GlobalUpgrade#ordinal()}
 */
public record ProgressionInfo(float wallet, float bank, float bankCap, boolean autoDeposit,
                              int[] globalLevels) {

    public static ProgressionInfo empty() {
        return new ProgressionInfo(0.0F, 0.0F, 0.0F, true,
                new int[GlobalUpgrade.values().length]);
    }

    /** Whether the player owns a given ability. */
    public boolean has(GlobalUpgrade ability) {
        int index = ability.ordinal();
        return index < globalLevels.length && globalLevels[index] > 0;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(wallet);
        buf.writeFloat(bank);
        buf.writeFloat(bankCap);
        buf.writeBoolean(autoDeposit);
        buf.writeVarIntArray(globalLevels);
    }

    public static ProgressionInfo read(FriendlyByteBuf buf) {
        float wallet = buf.readFloat();
        float bank = buf.readFloat();
        float bankCap = buf.readFloat();
        boolean auto = buf.readBoolean();
        int[] levels = buf.readVarIntArray();
        int[] normalised = new int[GlobalUpgrade.values().length];
        System.arraycopy(levels, 0, normalised, 0, Math.min(levels.length, normalised.length));
        return new ProgressionInfo(wallet, bank, bankCap, auto, normalised);
    }
}
