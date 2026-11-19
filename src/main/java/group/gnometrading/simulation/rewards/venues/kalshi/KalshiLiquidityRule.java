package group.gnometrading.simulation.rewards.venues.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import group.gnometrading.simulation.rewards.LiquidityRule;
import group.gnometrading.simulation.rewards.SideLevels;
import group.gnometrading.simulation.rewards.SideScore;
import group.gnometrading.sm.PriceGrid;
import group.gnometrading.sm.incentive.IncentiveProgram;

/**
 * Kalshi's Liquidity Incentive Program, as its terms set it out (CFTC filing of 2026-07-15, rules07152610358). At
 * each snapshot, for each side (a YES ask counts as a NO bid):
 *
 * <ul>
 *   <li>Walking from the best bid, whole price levels are added until their size reaches the target: every bid at a
 *       level added qualifies at its full size, including the level that reaches it. A side that never reaches it
 *       qualifies none.
 *   <li>The reference price is the first of those levels where the size reached a fifth of the target.
 *   <li>A bid scores its size times the discount factor once per tick it sits below the reference price, divided by
 *       the side's total.
 * </ul>
 *
 * <p>A snapshot counts only when both sides qualify, so a market's snapshot shares sum to 2. The reward is the period's
 * reward times our total share, divided by twice the period's snapshots, rounded down to the cent, and nothing under
 * $1 per market program.
 */
public final class KalshiLiquidityRule implements LiquidityRule {

    private static final long DOLLAR = 1_000_000_000L;
    private static final long CENT = DOLLAR / 100;
    private static final int SIDES = 2;
    private static final double REFERENCE_FRACTION = 5.0;

    /** A program version's terms: the size each side needs, in contracts, and the discount per tick. */
    record Terms(double targetSize, double discountFactor) {}

    @Override
    public Object terms(final IncentiveProgram version) {
        final JsonNode definition = version.definition();
        return new Terms(
                definition.get("targetSize").asDouble(),
                definition.get("discountFactor").asDouble());
    }

    @Override
    public void scoreSide(final SideLevels side, final Object terms, final PriceGrid grid, final SideScore out) {
        final Terms kalshi = (Terms) terms;
        final double target = kalshi.targetSize();
        final int levels = side.count();
        out.reset(levels);
        if (levels == 0) {
            return;
        }

        int reference = -1;
        double cumulative = 0;
        for (int i = 0; i < levels; i++) {
            cumulative += side.others(i) + side.ours(i);
            if (cumulative >= target / REFERENCE_FRACTION) {
                reference = i;
                break;
            }
        }
        if (reference < 0) {
            markShort(side, out, levels);
            return;
        }

        final long referencePrice = side.price(reference);
        cumulative = 0;
        double total = 0;
        for (int i = 0; i < levels; i++) {
            final double size = side.others(i) + side.ours(i);
            final double multiplier = i <= reference ? 1.0 : discount(kalshi, referencePrice, side.price(i), grid);
            cumulative += size;
            total += size * multiplier;
            out.scoreOurs(i, side.ours(i) * multiplier);
            if (cumulative >= target) {
                out.qualify(total);
                return;
            }
        }
        markShort(side, out, levels);
    }

    private static void markShort(final SideLevels side, final SideScore out, final int levels) {
        out.reset(levels);
        if (side.depthFull()) {
            out.depthUnknown();
        }
    }

    /**
     * A tick is a step of the market's price grid, so on a banded grid a gap that crosses a band edge counts each band's
     * steps. The asks' gaps are counted in the plain grid; the complement's grid mirrors it, so the count is the same.
     */
    private static double discount(final Terms terms, final long reference, final long price, final PriceGrid grid) {
        return Math.pow(terms.discountFactor(), grid.ticksBetween(reference, price));
    }

    @Override
    public double rewardPerShareNano(final IncentiveProgram version) {
        final long period = version.periodEndNanos() - version.periodStartNanos();
        return period <= 0 ? 0 : (double) version.reward() / (SIDES * (double) period);
    }

    @Override
    public long close(final long gross) {
        final long cents = gross / CENT * CENT;
        return cents < DOLLAR ? 0 : cents;
    }
}
