package com.dsh.maidmanager.logic;

/**
 * Works out how much of a shrine lamp's stored P-points may be moved into the bank.
 *
 * <p>Kept pure and separate because the numbers involved are all other people's: the lamp's
 * balance comes from TLM, its capacity from TLM's config, and the bank's room from ours. Getting
 * the arithmetic wrong either leaves points stranded or - worse - conjures them.
 */
public final class LampDrain {

    private LampDrain() {
    }

    /**
     * How many points can be taken from a lamp right now.
     *
     * @param stored   what the lamp holds
     * @param reserve  left behind so the lamp's own buff is not starved
     * @param bankRoom free space in the bank
     * @return the amount to move, never negative and never more than exists
     */
    public static float drainable(float stored, float reserve, float bankRoom) {
        if (stored <= 0.0F || bankRoom <= 0.0F) {
            return 0.0F;
        }
        // A negative reserve would mean "take more than is there", so it is clamped to zero.
        float keep = Math.max(0.0F, reserve);
        float aboveReserve = stored - keep;
        if (aboveReserve <= 0.0F) {
            return 0.0F;
        }
        return Math.min(aboveReserve, bankRoom);
    }

    /**
     * The reserve to leave in a lamp: its own per-effect cost, so its buff keeps running.
     *
     * @param effectCost TLM's configured cost per effect application, 0 when unknown
     * @param configured the player's own setting, which may be higher
     */
    public static float reserveFor(float effectCost, float configured) {
        return Math.max(Math.max(0.0F, effectCost), Math.max(0.0F, configured));
    }

    /**
     * How many P-point items a bank balance can absorb.
     *
     * <p>Rounded down on purpose: a partial item cannot be taken, and rounding up would credit
     * points the player did not hand over.
     *
     * @param bankRoom free space in the bank
     * @param perItem  the value credited for one item
     * @param held     how many the player actually has
     */
    public static int itemsAffordable(float bankRoom, float perItem, int held) {
        if (held <= 0 || bankRoom <= 0.0F || perItem <= 0.0F) {
            return 0;
        }
        int fits = (int) Math.floor(bankRoom / perItem);
        if (fits <= 0) {
            return 0;
        }
        return Math.min(fits, held);
    }
}
