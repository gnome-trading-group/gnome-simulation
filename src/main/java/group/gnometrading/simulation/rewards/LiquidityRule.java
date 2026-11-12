package group.gnometrading.simulation.rewards;

import group.gnometrading.sm.incentive.IncentiveProgram;

/**
 * A venue's liquidity program rules: how it scores one side of a book, what a unit of score is worth, and how it pays
 * a period out. Each venue's rules live in its own class; the scorer holds none.
 */
public interface LiquidityRule {

    /** The venue's scoring terms from a program version's definition, read once when the program is bound. */
    Object terms(IncentiveProgram version);

    /**
     * Scores one side of a market, its levels best first, at a moment the book is as given: fills {@code out} with
     * whether the side qualifies, its total score, and our score at each level. {@code tick} is the listing's price
     * tick then. Never allocates.
     */
    void scoreSide(SideLevels side, Object terms, long tick, SideScore out);

    /**
     * The reward, in price units, that one nanosecond of a full share of one side earns under a program version. A
     * share of the whole market (every side) for the version's whole period then earns its reward.
     */
    double rewardPerShareNano(IncentiveProgram version);

    /** What the venue would pay for a period whose gross estimate is {@code gross} price units. */
    long close(long gross);
}
