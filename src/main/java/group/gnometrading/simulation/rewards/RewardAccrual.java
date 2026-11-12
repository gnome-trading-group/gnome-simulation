package group.gnometrading.simulation.rewards;

import group.gnometrading.sm.incentive.IncentiveProgram;

/**
 * What one market's incentive program has accrued so far: our gross reward and share-time on each of the market's
 * listings, and how the program's time divided between counting, one-sided, depth unknown and no data. Times are nanoseconds; rewards are price units (1e9 = $1), before the venue's closing rules.
 */
public final class RewardAccrual {

    /** The market's plain listing (YES on Kalshi). */
    public static final int PLAIN = 0;
    /** The market's complement listing (NO on Kalshi). */
    public static final int COMPLEMENT = 1;

    private static final int LISTINGS = 2;

    private final String marketKey;
    private final String venueProgramId;
    private final LiquidityRule rule;
    private final int[] listingIds;
    private final double[] gross = new double[LISTINGS];
    private final double[] shareNanos = new double[LISTINGS];
    private IncentiveProgram latest;
    double countingNanos;
    double oneSidedNanos;
    double depthUnknownNanos;
    double noDataNanos;

    RewardAccrual(
            final String marketKey,
            final String venueProgramId,
            final LiquidityRule rule,
            final int[] listingIds,
            final IncentiveProgram latest) {
        this.marketKey = marketKey;
        this.venueProgramId = venueProgramId;
        this.rule = rule;
        this.listingIds = listingIds;
        this.latest = latest;
    }

    void latest(final IncentiveProgram version) {
        this.latest = version;
    }

    void add(final int slot, final double shareTime, final double reward) {
        this.shareNanos[slot] += shareTime;
        this.gross[slot] += reward;
    }

    public String marketKey() {
        return this.marketKey;
    }

    public String venueProgramId() {
        return this.venueProgramId;
    }

    /** The program's latest version, whose period the accrual runs over. */
    public IncentiveProgram program() {
        return this.latest;
    }

    /** The listing bound to {@link #PLAIN} or {@link #COMPLEMENT}, or -1 when none is. */
    public int listingId(final int slot) {
        return this.listingIds[slot];
    }

    /** Our gross reward on one of the market's listings, truncated to whole price units. */
    public long gross(final int slot) {
        return (long) this.gross[slot];
    }

    /** Our gross reward on the whole market. */
    public long marketGross() {
        return (long) (this.gross[PLAIN] + this.gross[COMPLEMENT]);
    }

    /** What the venue would pay for the market's period at our gross so far. */
    public long marketPaid() {
        return this.rule.close(marketGross());
    }

    /** Our share of a side, integrated over time: one nanosecond at the whole of one side is 1. */
    public double shareNanos(final int slot) {
        return this.shareNanos[slot];
    }

    /** Time the market counted toward the program: both sides met the venue's requirement. */
    public long countingNanos() {
        return (long) this.countingNanos;
    }

    /** Time a side fell short of the requirement, with the book shown in full. */
    public long oneSidedNanos() {
        return (long) this.oneSidedNanos;
    }

    /** Time a side fell short while the feed showed every level it carries, so it may really have qualified. */
    public long depthUnknownNanos() {
        return (long) this.depthUnknownNanos;
    }

    /** Time with no book to score: before the first one, after a gap, or with too many of our orders to track. */
    public long noDataNanos() {
        return (long) this.noDataNanos;
    }
}
