package group.gnometrading.simulation.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import group.gnometrading.simulation.rewards.venues.kalshi.KalshiLiquidityRule;
import group.gnometrading.sm.incentive.IncentiveProgram;
import group.gnometrading.sm.incentive.IncentiveTimeline;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewardScorerTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long CENT = 10_000_000L;
    private static final long DOLLAR = 100 * CENT;
    private static final long CONTRACT = 1_000_000L;
    private static final long T0 = 1_790_000_000L * SECOND;
    private static final long PERIOD = 10_000 * SECOND;
    private static final TickSize ONE_CENT = nanos -> CENT;
    private static final KalshiLiquidityRule KALSHI = new KalshiLiquidityRule();
    private static final int YES = 1;
    private static final int NO = 2;

    private static IncentiveProgram version(
            final long start,
            final long end,
            final long reward,
            final double target,
            final double discount,
            final long effectiveFrom) {
        return new IncentiveProgram(
                "k1",
                IncentiveProgram.IncentiveKind.LIQUIDITY,
                "M",
                start,
                end,
                reward,
                IncentiveProgram.RewardPer.PERIOD,
                JsonNodeFactory.instance.objectNode().put("targetSize", target).put("discountFactor", discount),
                false,
                effectiveFrom);
    }

    private static IncentiveTimeline timeline(final IncentiveProgram... versions) {
        return new IncentiveTimeline(List.<IncentiveProgram[]>of(versions));
    }

    /** $100 over 10,000 s, 1,000 contracts a side, no discount. */
    private static IncentiveTimeline example() {
        return timeline(version(T0, T0 + PERIOD, 100 * DOLLAR, 1000, 1.0, T0));
    }

    private static RewardScorer scorer(
            final IncentiveTimeline programs, final boolean plain, final boolean complement) {
        final RewardScorer scorer = new RewardScorer(RewardScorer.Mode.INSERT_OURS, 8, 64);
        if (plain) {
            scorer.bind(YES, "M", false, KALSHI, programs, ONE_CENT);
        }
        if (complement) {
            scorer.bind(NO, "M", true, KALSHI, programs, ONE_CENT);
        }
        return scorer;
    }

    /** A book from (price in cents, contracts) pairs per side, best first. */
    private static void book(
            final RewardScorer scorer, final int listing, final long at, final long[][] bids, final long[][] asks) {
        final long[] bidPrice = new long[10];
        final long[] bidSize = new long[10];
        final long[] askPrice = new long[10];
        final long[] askSize = new long[10];
        for (int i = 0; i < bids.length; i++) {
            bidPrice[i] = bids[i][0] * CENT;
            bidSize[i] = bids[i][1] * CONTRACT;
        }
        for (int i = 0; i < asks.length; i++) {
            askPrice[i] = asks[i][0] * CENT;
            askSize[i] = asks[i][1] * CONTRACT;
        }
        scorer.onBook(listing, at, bidPrice, bidSize, askPrice, askSize);
    }

    private static final long[][] BIDS = {{50, 2000}};
    private static final long[][] ASKS = {{52, 2000}};
    private static final long[][] NONE = {};

    private static RewardAccrual only(final RewardScorer scorer) {
        assertEquals(1, scorer.accrualCount());
        return scorer.accrual(0);
    }

    @Test
    void kalshisWorkedExampleEarnsSixteenDollars() {
        final RewardScorer scorer = scorer(example(), true, false);
        book(scorer, YES, T0, BIDS, ASKS);
        // 500 a side, a tick better than everyone else's 2,000: 20% of each side under either reading.
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        scorer.onOrder(YES, 2, false, 51 * CENT, 500 * CONTRACT, T0);
        book(scorer, YES, T0 + 8000 * SECOND, BIDS, NONE);
        scorer.advanceTo(T0 + PERIOD + SECOND);

        final RewardAccrual accrual = only(scorer);
        assertEquals(16 * DOLLAR, accrual.marketGross(), 1);
        assertEquals(16 * DOLLAR, accrual.marketPaid());
        assertEquals(8000 * SECOND, accrual.countingNanos());
        assertEquals(2000 * SECOND, accrual.oneSidedNanos());
        assertEquals(0, accrual.noDataNanos());
    }

    @Test
    void theComplementListingsOrdersBidForTheComplementInItsOwnTerms() {
        final RewardScorer scorer = scorer(example(), true, true);
        book(scorer, YES, T0, BIDS, ASKS);
        // The NO listing's own book is never scored while the YES listing's is bound.
        book(scorer, NO, T0, new long[][] {{1, 1}}, new long[][] {{99, 1}});
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        // Buying NO at 49c is the YES ask at 51c.
        scorer.onOrder(NO, 2, true, 49 * CENT, 500 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD);

        final RewardAccrual accrual = only(scorer);
        assertEquals(10 * DOLLAR, accrual.gross(RewardAccrual.PLAIN), 1);
        assertEquals(10 * DOLLAR, accrual.gross(RewardAccrual.COMPLEMENT), 1);
        assertEquals(YES, accrual.listingId(RewardAccrual.PLAIN));
        assertEquals(NO, accrual.listingId(RewardAccrual.COMPLEMENT));
    }

    @Test
    void aComplementBookAloneIsReadInThePlainTerms() {
        final RewardScorer scorer = scorer(example(), false, true);
        // NO bids at 48c are YES asks at 52c; NO asks at 50c are YES bids at 50c.
        book(scorer, NO, T0, new long[][] {{48, 2000}}, new long[][] {{50, 2000}});
        // Selling NO at 49c bids YES at 51c; buying NO at 49c is the YES ask at 51c.
        scorer.onOrder(NO, 1, false, 49 * CENT, 500 * CONTRACT, T0);
        scorer.onOrder(NO, 2, true, 49 * CENT, 500 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD);

        assertEquals(20 * DOLLAR, only(scorer).gross(RewardAccrual.COMPLEMENT), 1);
    }

    @Test
    void everyBidAtTheLevelThatReachesTheTargetQualifiesWhateverItsPlaceInTheQueue() {
        final RewardScorer scorer = scorer(example(), true, false);
        // Everyone else's 1,000 at 50c already meets the target; our 200 behind them at 50c still qualify.
        book(scorer, YES, T0, new long[][] {{50, 1000}}, new long[][] {{52, 1000}});
        scorer.onOrder(YES, 1, true, 50 * CENT, 200 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD);

        // 200 of the 1,200 on the YES side, the whole period: $100 × (1/6) ÷ 2.
        assertEquals(100 * DOLLAR / 12, only(scorer).gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void aSideWhoseBestBidIsAtTheHighestPriceQualifiesNone() {
        final RewardScorer scorer = scorer(example(), true, false);
        // A YES ask at 1c is a NO bid at 99c, the highest price a NO bid can have.
        book(scorer, YES, T0, BIDS, new long[][] {{1, 2000}, {2, 2000}});
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        book(scorer, YES, T0 + 4000 * SECOND, BIDS, new long[][] {{2, 2000}});
        scorer.advanceTo(T0 + PERIOD);

        final RewardAccrual accrual = only(scorer);
        assertEquals(4000 * SECOND, accrual.oneSidedNanos());
        assertEquals(6000 * SECOND, accrual.countingNanos());
        assertEquals(6 * DOLLAR, accrual.gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void levelsBehindTheReferencePriceAreDiscountedPerTick() {
        final IncentiveTimeline halves = timeline(version(T0, T0 + PERIOD, 100 * DOLLAR, 1000, 0.5, T0));
        final RewardScorer scorer = scorer(halves, true, false);
        // The reference is 54c, where size first reaches 200; 53c is a tick behind and 51c three.
        book(scorer, YES, T0, new long[][] {{55, 100}, {54, 100}, {53, 400}, {51, 400}}, new long[][] {{60, 1000}});
        scorer.onOrder(YES, 1, true, 51 * CENT, 100 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD);

        // Scores: 100 + 100 + 400×0.5 + 500×0.125, ours 100×0.125 of the 462.5.
        final double share = 12.5 / 462.5;
        assertEquals((long) (100 * DOLLAR * share / 2), only(scorer).gross(RewardAccrual.PLAIN), 2);
    }

    @Test
    void aSideShortOfTheTargetWithEveryLevelShownCountsAsDepthUnknown() {
        final RewardScorer scorer = scorer(example(), true, false);
        final long[][] tenThin = new long[10][];
        for (int i = 0; i < 10; i++) {
            tenThin[i] = new long[] {50 - i, 10};
        }
        book(scorer, YES, T0, tenThin, ASKS);
        scorer.advanceTo(T0 + PERIOD);

        final RewardAccrual accrual = only(scorer);
        assertEquals(PERIOD, accrual.depthUnknownNanos());
        assertEquals(0, accrual.countingNanos());
    }

    @Test
    void aRewardChangedMidPeriodAppliesFromWhenItTookEffect() {
        final long half = T0 + PERIOD / 2;
        final IncentiveTimeline raised = timeline(
                version(T0, T0 + PERIOD, 100 * DOLLAR, 1000, 1.0, T0),
                version(T0, T0 + PERIOD, 300 * DOLLAR, 1000, 1.0, half));
        final RewardScorer scorer = scorer(raised, true, false);
        book(scorer, YES, T0, BIDS, ASKS);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD);

        // 20% of one side: $100 × 0.2 ÷ 2 for the first half, $300 × 0.2 ÷ 2 for the second.
        assertEquals(20 * DOLLAR, only(scorer).gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void aProgramCancelledBeforeItStartsEarnsNothing() {
        final IncentiveTimeline cancelled = timeline(
                version(T0, T0 + PERIOD, 100 * DOLLAR, 1000, 1.0, T0 - 100 * SECOND),
                version(T0, T0 - 50 * SECOND, 100 * DOLLAR, 1000, 1.0, T0 - 50 * SECOND));
        final RewardScorer scorer = scorer(cancelled, true, false);
        book(scorer, YES, T0, BIDS, ASKS);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD);

        final RewardAccrual accrual = only(scorer);
        assertEquals(0, accrual.marketGross());
        assertEquals(0, accrual.countingNanos());
    }

    @Test
    void nothingAccruesOutsideThePeriod() {
        final RewardScorer scorer = scorer(example(), true, false);
        book(scorer, YES, T0 - 500 * SECOND, BIDS, ASKS);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0 - 500 * SECOND);
        scorer.advanceTo(T0 + PERIOD);
        final long atEnd = only(scorer).gross(RewardAccrual.PLAIN);
        scorer.advanceTo(T0 + 3 * PERIOD);

        assertEquals(10 * DOLLAR, atEnd, 1);
        assertEquals(atEnd, only(scorer).gross(RewardAccrual.PLAIN));
        assertEquals(PERIOD, only(scorer).countingNanos());
    }

    @Test
    void timeBeforeTheFirstBookAndAfterAGapHasNoData() {
        final RewardScorer scorer = scorer(example(), true, false);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        book(scorer, YES, T0 + 1000 * SECOND, BIDS, ASKS);
        scorer.onGap(YES, T0 + 2000 * SECOND);
        book(scorer, YES, T0 + 4000 * SECOND, BIDS, ASKS);
        scorer.advanceTo(T0 + PERIOD);

        final RewardAccrual accrual = only(scorer);
        assertEquals(3000 * SECOND, accrual.noDataNanos());
        assertEquals(7000 * SECOND, accrual.countingNanos());
    }

    @Test
    void timeBeforeTheScorerBeganIsNotScoredAtAll() {
        final RewardScorer scorer = scorer(example(), true, false);
        scorer.beginAt(T0 + 4000 * SECOND);
        book(scorer, YES, T0 + 5000 * SECOND, BIDS, ASKS);
        scorer.advanceTo(T0 + PERIOD);

        final RewardAccrual accrual = only(scorer);
        assertEquals(1000 * SECOND, accrual.noDataNanos());
        assertEquals(5000 * SECOND, accrual.countingNanos());
    }

    private static IncentiveProgram named(
            final String id, final long start, final long end, final long reward, final long effectiveFrom) {
        return new IncentiveProgram(
                id,
                IncentiveProgram.IncentiveKind.LIQUIDITY,
                "M",
                start,
                end,
                reward,
                IncentiveProgram.RewardPer.PERIOD,
                JsonNodeFactory.instance.objectNode().put("targetSize", 1000.0).put("discountFactor", 1.0),
                false,
                effectiveFrom);
    }

    private static IncentiveTimeline programs(final IncentiveProgram[]... programs) {
        return new IncentiveTimeline(List.of(programs));
    }

    @Test
    void reloadedProgramsKeepWhatTheyAccruedAndNewOnesStartFromThen() {
        final RewardScorer scorer = scorer(example(), true, false);
        book(scorer, YES, T0, BIDS, ASKS);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD / 2);

        // The same program, now known to pay $300 from halfway, and a new one running the same period.
        scorer.setPrograms(
                YES,
                programs(
                        new IncentiveProgram[] {
                            named("k1", T0, T0 + PERIOD, 100 * DOLLAR, T0),
                            named("k1", T0, T0 + PERIOD, 300 * DOLLAR, T0 + PERIOD / 2)
                        },
                        new IncentiveProgram[] {named("k2", T0, T0 + PERIOD, 100 * DOLLAR, T0)}));
        scorer.advanceTo(T0 + PERIOD);

        assertEquals(2, scorer.accrualCount());
        // k1: 20% of one side, $100 ÷ 2 × 0.2 for the first half and $300 ÷ 2 × 0.2 for the second.
        assertEquals(20 * DOLLAR, scorer.accrual(0).gross(RewardAccrual.PLAIN), 1);
        assertEquals(300 * DOLLAR, scorer.accrual(0).program().reward());
        // k2 only from the reload: $100 ÷ 2 × 0.2 × 0.5.
        assertEquals(5 * DOLLAR, scorer.accrual(1).gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void aReloadDoesNotAddProgramsThatHaveAlreadyEnded() {
        final RewardScorer scorer = scorer(example(), true, false);
        scorer.advanceTo(T0 + PERIOD + SECOND);
        scorer.setPrograms(YES, programs(new IncentiveProgram[] {named("old", T0, T0 + PERIOD, DOLLAR, T0)}));

        assertEquals(1, scorer.accrualCount());
    }

    @Test
    void endedProgramsAreRetiredOnceRead() {
        final RewardScorer scorer = scorer(example(), true, false);
        scorer.setPrograms(
                YES,
                programs(
                        new IncentiveProgram[] {named("k1", T0, T0 + PERIOD, 100 * DOLLAR, T0)},
                        new IncentiveProgram[] {named("later", T0, T0 + 2 * PERIOD, 100 * DOLLAR, T0)}));
        scorer.advanceTo(T0 + PERIOD + SECOND);

        assertEquals(1, scorer.retireEndedBefore(T0 + PERIOD + SECOND));
        assertEquals(1, scorer.accrualCount());
        assertEquals("later", scorer.accrual(0).venueProgramId());
    }

    @Test
    void anEventStampedInThePastIsTakenAtTheLatestTime() {
        final RewardScorer scorer = scorer(example(), true, false);
        book(scorer, YES, T0, BIDS, ASKS);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0 + 5000 * SECOND);
        // Cancelled "earlier" than it was placed: taken at the order's time, so it never rested.
        scorer.onOrder(YES, 1, true, 51 * CENT, 0, T0 + 1000 * SECOND);
        scorer.advanceTo(T0 + PERIOD);

        assertEquals(0, only(scorer).gross(RewardAccrual.PLAIN));
    }

    @Test
    void oursInTheBookAreTakenOutToFindEveryoneElses() {
        final RewardScorer scorer = new RewardScorer(RewardScorer.Mode.OURS_IN_BOOK, 8, 64);
        scorer.bind(YES, "M", false, KALSHI, example(), ONE_CENT);
        // The venue's book already holds our 500 at 51c.
        book(scorer, YES, T0, new long[][] {{51, 500}, {50, 2000}}, ASKS);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        scorer.advanceTo(T0 + PERIOD);

        assertEquals(10 * DOLLAR, only(scorer).gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void tooManyOrdersOnAMarketScoreItAsNoDataInsteadOfAllocating() {
        final RewardScorer scorer = new RewardScorer(RewardScorer.Mode.INSERT_OURS, 1, 4);
        scorer.bind(YES, "M", false, KALSHI, example(), ONE_CENT);
        book(scorer, YES, T0, BIDS, ASKS);
        scorer.onOrder(YES, 1, true, 51 * CENT, 500 * CONTRACT, T0);
        scorer.onOrder(YES, 2, false, 51 * CENT, 500 * CONTRACT, T0 + 1000 * SECOND);
        scorer.advanceTo(T0 + PERIOD);

        assertEquals(1, scorer.ordersDropped());
        assertEquals(9000 * SECOND, only(scorer).noDataNanos());
    }

    @Test
    void aMarketTakesOneListingPerSide() {
        final RewardScorer scorer = scorer(example(), true, false);
        assertThrows(IllegalArgumentException.class, () -> scorer.bind(3, "M", false, KALSHI, example(), ONE_CENT));
    }

    @Test
    void kalshiPaysWholeCentsAndNothingUnderADollar() {
        assertEquals(0, KALSHI.close(DOLLAR - 1));
        assertEquals(DOLLAR, KALSHI.close(DOLLAR));
        assertEquals(123 * CENT, KALSHI.close(123 * CENT + CENT - 1));
    }
}
