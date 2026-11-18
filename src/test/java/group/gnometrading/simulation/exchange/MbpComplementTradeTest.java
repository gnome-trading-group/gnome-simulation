package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeSingleLevelUpdate;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTwoLevelUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Side;
import group.gnometrading.simulation.book.LocalOrder;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.book.TradePairing;
import group.gnometrading.simulation.config.ExchangeSimulationConfig;
import group.gnometrading.simulation.config.FeeModelConfig;
import group.gnometrading.simulation.config.LatencyConfig;
import group.gnometrading.simulation.config.QueueModelConfig;
import group.gnometrading.sm.fee.FeeTimeline;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Polymarket International matches an order on one outcome against an order on the other and prints the trade once, on
 * the outcome the taker traded, as one print of the taker's whole order at its average price. Each outcome's book shows
 * the other's orders at {@code 1 - price}, so a print on the other outcome is a trade against this book there.
 */
class MbpComplementTradeTest {

    private static final long MILLI = 1_000_000L;
    private static final long CENT = 10_000_000L;
    private static final group.gnometrading.schemas.book.Side BUY = group.gnometrading.schemas.book.Side.Bid;
    private static final group.gnometrading.schemas.book.Side SELL = group.gnometrading.schemas.book.Side.Ask;
    private static final TradePairing PM_INTL = TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25));

    private static SimulatedExchange exchange(boolean sweepingPrints) {
        return new ExchangeSimulationConfig(
                        new FeeModelConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new QueueModelConfig.RiskAverse(),
                        SelfTradePrevention.CANCEL_INCOMING,
                        PM_INTL,
                        sweepingPrints)
                .toSimulatedExchange(1, 7L, id -> FeeTimeline.EMPTY);
    }

    private static Mbp10Schema at(Mbp10Schema record, long nanos) {
        record.encoder.timestampEvent(nanos);
        return record;
    }

    private static long filled(List<OrderExecutionReport> reports) {
        return reports.stream()
                .filter(r -> r.decoder.execType() == ExecType.FILL || r.decoder.execType() == ExecType.PARTIAL_FILL)
                .mapToLong(r -> r.decoder.filledQty())
                .sum();
    }

    private static long cents(long n) {
        return n * CENT;
    }

    /** Outcome A showing 40c x 100 bid / 42c x 50 ask, with our bid of 10 at 40c behind the 100. */
    private static SimulatedExchange withOurBidBehindAHundred(boolean sweepingPrints) {
        SimulatedExchange a = exchange(sweepingPrints);
        a.onMarketData(at(makeSingleLevelUpdate(cents(40), 100, cents(42), 50), 0));
        Immediate.submit(a, makeLimitOrder(cents(40), 10, Side.Bid, 1L));
        return a;
    }

    /** A's 40c level leaves the book (the venue took the mint off it first), stamped 30 ms before the print. */
    private static void levelEmptiedBeforeThePrint(SimulatedExchange a) {
        a.onMarketData(at(makeSingleLevelUpdate(cents(39), 30, cents(42), 50), 70 * MILLI));
    }

    @Nested
    class AMint {

        @Test
        void aTakerBuyingTheOtherOutcomeFillsOurBidThroughTheQueueOnceItsShrinkIsClaimed() {
            SimulatedExchange a = withOurBidBehindAHundred(true);
            levelEmptiedBeforeThePrint(a);

            List<OrderExecutionReport> reports = a.onComplementTrade(at(makeTrade(BUY, cents(60), 110), 100 * MILLI));

            assertEquals(10, filled(reports), "110 bought on the other outcome: the 100 ahead of us, then our 10");
            assertEquals(cents(40), reports.get(reports.size() - 1).decoder.fillPrice());
        }

        @Test
        void aMintNoLargerThanTheQueueAheadLeavesUsUnfilled() {
            SimulatedExchange a = withOurBidBehindAHundred(true);
            levelEmptiedBeforeThePrint(a);

            assertEquals(0, filled(a.onComplementTrade(at(makeTrade(BUY, cents(60), 100), 100 * MILLI))));
            LocalOrder ours = a.restingOrder(1L);
            assertEquals(0, ours.phantomVolume, "the 100 ahead traded, so we are first now");
        }

        @Test
        void withoutThePrintOnTheOtherOutcomeTheShrinkReadsAsCancelsAndNothingFills() {
            SimulatedExchange a = withOurBidBehindAHundred(true);
            levelEmptiedBeforeThePrint(a);
            assertEquals(10, a.restingOrder(1L).remaining, "still resting, never filled");
        }

        @Test
        void bookRecordsOfTheOtherOutcomeAreNotTradesHere() {
            SimulatedExchange a = withOurBidBehindAHundred(true);
            List<OrderExecutionReport> reports =
                    a.onComplementTrade(at(makeSingleLevelUpdate(cents(58), 70, cents(60), 100), 50 * MILLI));
            assertTrue(reports.isEmpty());
            assertEquals(100, a.restingOrder(1L).phantomVolume, "the queue ahead is untouched");
        }
    }

    @Nested
    class ASweep {

        /** A showing 42c x 50 and 40c x 40 bids, with our 10 at 40c behind the 40. */
        private SimulatedExchange withOurBidOnTheSecondLevel(boolean sweepingPrints) {
            SimulatedExchange a = exchange(sweepingPrints);
            a.onMarketData(at(makeTwoLevelUpdate(cents(42), 50, cents(43), 30, cents(40), 40, cents(44), 30), 0));
            Immediate.submit(a, makeLimitOrder(cents(40), 10, Side.Bid, 1L));
            return a;
        }

        @Test
        void aMintPrintedAtItsAveragePriceReachesTheDeeperLevelItSwept() {
            // 100 bought on the other outcome at 58c x 50 then 60c x 50 prints once at its average, 59c: on A that took
            // 42c x 50 and then 40c, past the 41c the print reads as.
            SimulatedExchange a = withOurBidOnTheSecondLevel(true);
            assertEquals(10, filled(a.onComplementTrade(at(makeTrade(BUY, cents(59), 100), 10 * MILLI))));
        }

        @Test
        void aVenueWhosePrintsStopAtTheirPriceNeverReachesThatLevel() {
            SimulatedExchange a = withOurBidOnTheSecondLevel(false);
            assertEquals(0, filled(a.onComplementTrade(at(makeTrade(BUY, cents(59), 100), 10 * MILLI))));
        }

        @Test
        void aPrintOnOurOwnOutcomeAlsoSweepsPastItsAveragePrice() {
            SimulatedExchange sweeping = withOurBidOnTheSecondLevel(true);
            assertEquals(10, filled(sweeping.onMarketData(at(makeTrade(SELL, cents(41), 100), 10 * MILLI))));

            SimulatedExchange capped = withOurBidOnTheSecondLevel(false);
            assertEquals(0, filled(capped.onMarketData(at(makeTrade(SELL, cents(41), 100), 10 * MILLI))));
        }
    }

    @Nested
    class AMerge {

        @Test
        void aTakerSellingTheOtherOutcomeFillsOurAsk() {
            SimulatedExchange a = exchange(true);
            a.onMarketData(at(makeSingleLevelUpdate(cents(58), 30, cents(60), 20), 0));
            Immediate.submit(a, makeLimitOrder(cents(60), 5, Side.Ask, 2L));

            List<OrderExecutionReport> reports = a.onComplementTrade(at(makeTrade(SELL, cents(40), 25), 10 * MILLI));

            assertEquals(5, filled(reports), "25 sold on the other outcome took the 20 ahead of us and our 5 at 60c");
            assertEquals(cents(60), reports.get(reports.size() - 1).decoder.fillPrice());
        }
    }
}
