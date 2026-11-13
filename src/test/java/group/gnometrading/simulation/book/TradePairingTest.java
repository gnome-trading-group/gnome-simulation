package group.gnometrading.simulation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.queues.OptimisticQueueModel;
import group.gnometrading.simulation.queues.QueueModel;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A recorded feed can deliver a trade's book change (its level shrinking by the traded size) before the trade record.
 * With pairing, the trade claims that shrink and the queue ends up as if the trade had come first; without it, the
 * shrink reads as cancels and the trade takes the same volume off the queue again.
 */
class TradePairingTest {

    private static final group.gnometrading.schemas.book.Side BUY = group.gnometrading.schemas.book.Side.Bid;
    private static final group.gnometrading.schemas.book.Side SELL = group.gnometrading.schemas.book.Side.Ask;
    private static final TradePairing PM_INTL = TradePairing.within(Duration.ofMillis(50), Duration.ZERO);
    private static final TradePairing PM_US = TradePairing.within(Duration.ZERO, Duration.ofMillis(50));

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    private static Order order(Side side, long price, long size, long clientOid) {
        Order order = new Order();
        order.encoder
                .exchangeId((short) 1)
                .securityId(1)
                .price(price)
                .size(size)
                .side(side)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encodeClientOid(clientOid, 0);
        return order;
    }

    private static List<BidAskLevel> top(long askSize) {
        return List.of(new BidAskLevel(45, 100, 47, askSize));
    }

    private static long filled(List<LocalOrderFill> fills) {
        return fills.stream().mapToLong(LocalOrderFill::fillSize).sum();
    }

    /** A book showing 45 x 100 / 47 x {@code askSize} at time 0, with our ask resting at 47. */
    private static MbpBook bookWithOurAsk(QueueModel model, TradePairing pairing, long askSize, long ourSize) {
        MbpBook book = new MbpBook(model, pairing);
        book.onMarketUpdate(top(askSize), 10, 0);
        book.addLocalOrder(order(Side.Ask, 47, ourSize, 1L));
        return book;
    }

    private static long phantom(MbpBook book) {
        return book.findLocalOrder(1L).phantomVolume;
    }

    private static long askSize(MbpBook book, long price) {
        return book.asks().get(price).size;
    }

    @Nested
    class TheDouble {

        @Test
        void withoutPairingABookFirstTradeIsChargedTwiceSoTheTradeThatClearsTheLevelFillsUs() {
            MbpBook book = new MbpBook(new RiskAverseQueueModel());
            book.onMarketUpdate(top(100), 10);
            book.addLocalOrder(order(Side.Ask, 47, 50, 1L));

            long fills = 0;
            for (int i = 1; i <= 10; i++) {
                book.onMarketUpdate(top(100 - 10L * i), 10);
                fills += filled(book.onTrade(47, 10, BUY));
            }
            assertEquals(10, fills, "the default reads book changes and trades separately, as it always has");
        }

        @Test
        void tenBookFirstTradesGoToTheHundredAheadAndOnlyTheNextTradeFillsUs() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);

            long fills = 0;
            for (int i = 1; i <= 10; i++) {
                book.onMarketUpdate(top(100 - 10L * i), 10, ms(i));
                fills += filled(book.onTrade(47, 10, BUY, ms(i)));
            }
            assertEquals(0, fills, "every trade went to the 100 ahead of us");
            assertEquals(0, phantom(book));
            assertEquals(0, askSize(book, 47), "the level is as the book shows it, not lowered again by the trades");

            assertEquals(
                    10, filled(book.onTrade(47, 10, BUY, ms(11))), "with nobody left ahead, the next trade is ours");
        }

        @Test
        void aLevelEmptiedByATradeFillsTheMakerWhoWasThereNotUs() {
            MbpBook book = new MbpBook(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP);
            book.onMarketUpdate(List.of(new BidAskLevel(40, 7, 46, 1), new BidAskLevel(39, 7, 47, 2456)), 10, 0);
            book.addLocalOrder(order(Side.Ask, 46, 500, 1L));

            // As recorded on KXRT-HOWT-60 at 2026-11-11 05:25:54.746: the 1 at 46 is taken, then the trade of 1 at 46.
            book.onMarketUpdate(List.of(new BidAskLevel(40, 7, 47, 2456)), 10, ms(10));
            assertEquals(0, filled(book.onTrade(46, 1, BUY, ms(10))), "the 1 at 46 belonged to the maker ahead of us");

            assertEquals(1, filled(book.onTrade(46, 1, BUY, ms(20))), "we are first at 46 now");
        }
    }

    @Nested
    class ArrivalOrder {

        @Test
        void aTradeThatArrivesFirstIsReadAsTodayWithOrWithoutPairing() {
            for (TradePairing pairing : List.of(TradePairing.OFF, TradePairing.SAME_TIMESTAMP, PM_INTL, PM_US)) {
                MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), pairing, 100, 50);
                assertEquals(0, filled(book.onTrade(47, 10, BUY, ms(10))));
                book.onMarketUpdate(top(90), 10, ms(10));
                assertEquals(90, phantom(book), pairing.toString());
                assertEquals(90, askSize(book, 47), pairing.toString());
            }
        }

        @Test
        void aCancelAndATradeInTheSameMillisecondLeaveOnlyTheCancelAsACancel() {
            // Cancels ahead of us: the 30 off the level is a trade of 10 and a cancel of 20, both ahead.
            MbpBook paired = bookWithOurAsk(new OptimisticQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            paired.onMarketUpdate(top(70), 10, ms(10));
            paired.onTrade(47, 10, BUY, ms(10));

            MbpBook tradeFirst = bookWithOurAsk(new OptimisticQueueModel(), TradePairing.OFF, 100, 50);
            tradeFirst.onTrade(47, 10, BUY, ms(10));
            tradeFirst.onMarketUpdate(top(70), 10, ms(10));

            MbpBook unpaired = bookWithOurAsk(new OptimisticQueueModel(), TradePairing.OFF, 100, 50);
            unpaired.onMarketUpdate(top(70), 10, ms(10));
            unpaired.onTrade(47, 10, BUY, ms(10));

            assertEquals(70, phantom(tradeFirst));
            assertEquals(70, phantom(paired), "as if the trade had come first");
            assertEquals(60, phantom(unpaired), "the trade's 10 taken off the queue twice");
            assertEquals(70, askSize(paired, 47));
        }

        @Test
        void twoTradesClaimOneShrinkBetweenThemAndAThirdIsANewTrade() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            book.onMarketUpdate(top(80), 10, ms(10));
            book.onTrade(47, 10, BUY, ms(10));
            assertEquals(80, phantom(book), "the other 10 is still a cancel until a trade claims it");
            book.onTrade(47, 10, BUY, ms(10));
            assertEquals(80, phantom(book));
            assertEquals(80, askSize(book, 47));

            book.onTrade(47, 10, BUY, ms(10));
            assertEquals(70, phantom(book), "nothing left to claim: the book hasn't shown this trade yet");
            assertEquals(70, askSize(book, 47));
        }

        @Test
        void aTradeLargerThanTheShrinkClaimsItAndTakesTheRestAsANewTrade() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            book.onMarketUpdate(top(95), 10, ms(10));
            book.onTrade(47, 15, BUY, ms(10));
            assertEquals(85, phantom(book));
            assertEquals(85, askSize(book, 47));

            book.onMarketUpdate(top(85), 10, ms(10));
            assertEquals(85, phantom(book), "the book catching up with the rest of the trade is no cancel");
        }

        @Test
        void aTradeStillFillsUsWhenItReachesPastTheQueueAhead() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 5, 50);
            book.onMarketUpdate(top(100), 10, ms(5));
            assertEquals(5, phantom(book), "the 95 that joined are behind us");

            book.onMarketUpdate(top(80), 10, ms(10));
            assertEquals(15, filled(book.onTrade(47, 20, BUY, ms(10))), "5 to the maker ahead, then 15 to us");
            assertEquals(35, book.findLocalOrder(1L).remaining);
            assertEquals(0, phantom(book));
        }

        @Test
        void aSweepPairsEachLevelWithItsOwnTrade() {
            MbpBook paired = new MbpBook(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP);
            MbpBook unpaired = new MbpBook(new RiskAverseQueueModel(), TradePairing.OFF);
            for (MbpBook book : List.of(paired, unpaired)) {
                book.onMarketUpdate(
                        List.of(new BidAskLevel(45, 100, 47, 10), new BidAskLevel(44, 100, 48, 100)), 10, 0);
                book.addLocalOrder(order(Side.Ask, 48, 50, 1L));
                // A buy of 20 takes the 10 at 47 and 10 at 48; the book shows it, then one trade per level.
                book.onMarketUpdate(
                        List.of(new BidAskLevel(45, 100, 48, 90), new BidAskLevel(44, 100, Long.MIN_VALUE, 0)),
                        10,
                        ms(10));
                book.onTrade(47, 10, BUY, ms(10));
                book.onTrade(48, 10, BUY, ms(10));
            }
            assertEquals(90, phantom(paired));
            assertEquals(80, phantom(unpaired));
        }

        @Test
        void aShrinkOnOneSideIsNotClaimedByATradeOnTheOther() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            book.onMarketUpdate(List.of(new BidAskLevel(45, 90, 47, 90)), 10, ms(10));
            book.onTrade(45, 10, SELL, ms(10));
            assertEquals(90, phantom(book), "the sell hit the bids; our ask's shrink is still pending");
            book.onTrade(47, 10, BUY, ms(10));
            assertEquals(90, phantom(book));
            assertEquals(90, askSize(book, 47));
        }
    }

    @Nested
    class Bounds {

        @Test
        void aBookChangeStampedThirtyMillisecondsBeforeItsTradePairsUnderFiftyButNotAtTheSameTimestamp() {
            MbpBook intl = bookWithOurAsk(new RiskAverseQueueModel(), PM_INTL, 100, 50);
            MbpBook sameStamp = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            for (MbpBook book : List.of(intl, sameStamp)) {
                book.onMarketUpdate(top(90), 10, ms(100));
                book.onTrade(47, 10, BUY, ms(130));
            }
            assertEquals(90, phantom(intl));
            assertEquals(80, phantom(sameStamp));
        }

        @Test
        void aBookChangeStampedAfterItsTradeButDeliveredFirstPairsWhenTheVenueStampsThatWay() {
            MbpBook us = bookWithOurAsk(new RiskAverseQueueModel(), PM_US, 100, 50);
            MbpBook intl = bookWithOurAsk(new RiskAverseQueueModel(), PM_INTL, 100, 50);
            for (MbpBook book : List.of(us, intl)) {
                book.onMarketUpdate(top(90), 10, ms(120));
                book.onTrade(47, 10, BUY, ms(100));
            }
            assertEquals(90, phantom(us));
            assertEquals(80, phantom(intl), "this venue never stamps a trade's book change after it");
        }

        @Test
        void aBookChangeOutsideTheBoundsStaysACancel() {
            MbpBook before = bookWithOurAsk(new RiskAverseQueueModel(), PM_INTL, 100, 50);
            before.onMarketUpdate(top(90), 10, ms(100));
            before.onTrade(47, 10, BUY, ms(151));
            assertEquals(80, phantom(before));

            MbpBook after = bookWithOurAsk(new RiskAverseQueueModel(), PM_US, 100, 50);
            after.onMarketUpdate(top(90), 10, ms(151));
            after.onTrade(47, 10, BUY, ms(100));
            assertEquals(80, phantom(after));
        }

        @Test
        void shrinksTooFarApartForOneTradeAreSettledSoALaterTradeClaimsOnlyTheLaterOne() {
            MbpBook book = bookWithOurAsk(new OptimisticQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            book.onMarketUpdate(top(90), 10, ms(100));
            book.onMarketUpdate(top(80), 10, ms(5000));
            book.onTrade(47, 20, BUY, ms(5000));
            assertEquals(70, phantom(book), "the 10 at 100 ms was a cancel: the trade claims only the 10 at 5 s");
        }

        @Test
        void aBookThatPairsRefusesRecordsWithoutVenueTime() {
            MbpBook book = new MbpBook(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP);
            assertThrows(IllegalStateException.class, () -> book.onMarketUpdate(top(100), 10));
            assertThrows(IllegalStateException.class, () -> book.onTrade(47, 10, BUY));
        }
    }

    /** Nothing waits on a trade that may never come: a shrink is cancels from the moment it is seen. */
    @Nested
    class QuietBook {

        @Test
        void aShrinkNoTradeClaimsStaysACancelAndATradeFiftySecondsLaterIsItsOwnTrade() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            book.onMarketUpdate(top(90), 10, ms(100));
            assertEquals(90, phantom(book), "applied as a cancel at once");
            for (long t = 1; t <= 50; t++) {
                book.onMarketUpdate(top(90), 10, ms(100) + t * 1_000_000_000L);
            }
            assertEquals(90, phantom(book));

            book.onTrade(47, 10, BUY, ms(50_100));
            assertEquals(80, phantom(book));
            assertEquals(80, askSize(book, 47));
        }

        @Test
        void ourFillIsNeverHeldBackWaitingForAPairing() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 10, 50);
            // Everyone ahead of us cancels; the level stays only because our order is on it.
            book.onMarketUpdate(top(0), 10, ms(100));
            assertEquals(0, phantom(book));
            for (long t = 1; t <= 50; t++) {
                book.onMarketUpdate(top(0), 10, ms(100) + t * 1_000_000_000L);
            }
            assertEquals(10, filled(book.onTrade(47, 10, BUY, ms(50_100))), "the first trade in 50 s is ours");
        }

        @Test
        void aTradeRecordDeliveredLongAfterItsBookChangeStillPairsByItsStamp() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            book.onMarketUpdate(top(90), 10, ms(100));
            book.onMarketUpdate(top(120), 10, ms(200));
            for (long t = 1; t <= 50; t++) {
                book.onMarketUpdate(List.of(new BidAskLevel(45, 100 + t, 47, 120)), 10, ms(200) + t * 1_000_000_000L);
            }
            book.onTrade(47, 10, BUY, ms(100));
            assertEquals(
                    90, phantom(book), "the trade took 10 of the 100 ahead; the 30 that joined since are behind us");
            assertEquals(120, askSize(book, 47));
        }
    }

    @Nested
    class OurOrders {

        @Test
        void anOrderThatJoinedAfterTheShrinkDoesNotMoveUpForTheTrade() {
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 100, 50);
            book.onMarketUpdate(top(90), 10, ms(10));
            book.addLocalOrder(order(Side.Ask, 47, 50, 2L));
            assertEquals(90, book.findLocalOrder(2L).phantomVolume);

            book.onTrade(47, 10, BUY, ms(10));
            assertEquals(90, phantom(book));
            assertEquals(90, book.findLocalOrder(2L).phantomVolume, "it joined behind what was left after the trade");
        }

        @Test
        void anOrderThatJoinedAfterTheShrinkIsNotFilledByTheTrade() {
            // Our first order has 3 ahead of it and 7 behind; a buy of 10 empties the level.
            MbpBook book = bookWithOurAsk(new RiskAverseQueueModel(), TradePairing.SAME_TIMESTAMP, 3, 5);
            book.onMarketUpdate(top(10), 10, ms(5));
            book.onMarketUpdate(top(0), 10, ms(10));
            book.addLocalOrder(order(Side.Ask, 47, 50, 2L));

            List<LocalOrderFill> fills = book.onTrade(47, 10, BUY, ms(10));
            assertEquals(5, filled(fills), "3 to the market ahead, 5 to our first order, 2 to the market behind it");
            assertEquals(50, book.findLocalOrder(2L).remaining, "the trade happened before this order joined");
        }

        @Test
        void liquidityWeTookIsTreatedAsIfTheTradeCameFirst() {
            MbpBook paired = new MbpBook(new OptimisticQueueModel(), TradePairing.SAME_TIMESTAMP);
            MbpBook tradeFirst = new MbpBook(new OptimisticQueueModel(), TradePairing.SAME_TIMESTAMP);
            MbpBook unpaired = new MbpBook(new OptimisticQueueModel(), TradePairing.OFF);
            for (MbpBook book : List.of(paired, tradeFirst, unpaired)) {
                book.onMarketUpdate(top(100), 10, 0);
                book.consume(Side.Bid, List.of(new OrderMatch(47, 30)));
            }
            for (MbpBook book : List.of(paired, unpaired)) {
                book.onMarketUpdate(top(90), 10, ms(10));
                book.onTrade(47, 10, BUY, ms(10));
                book.onMarketUpdate(top(90), 10, ms(10));
            }
            tradeFirst.onTrade(47, 10, BUY, ms(10));
            tradeFirst.onMarketUpdate(top(90), 10, ms(10));
            tradeFirst.onMarketUpdate(top(90), 10, ms(10));

            assertEquals(60, tradeFirst.asks().get(47L).available());
            assertEquals(60, paired.asks().get(47L).available());
            assertEquals(70, unpaired.asks().get(47L).available(), "the trade read as cancels of what we took");
        }
    }
}
