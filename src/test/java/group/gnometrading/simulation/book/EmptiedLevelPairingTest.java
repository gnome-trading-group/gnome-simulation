package group.gnometrading.simulation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A feed that delivers a trade's book change first can empty the level the trade took before the print arrives. The
 * print must still be read as having taken that level, not as reaching past it into the next one.
 */
class EmptiedLevelPairingTest {

    private static final group.gnometrading.schemas.book.Side BUY = group.gnometrading.schemas.book.Side.Bid;
    private static final TradePairing PM_INTL = TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25));

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

    private static long filled(List<LocalOrderFill> fills) {
        return fills.stream().mapToLong(LocalOrderFill::fillSize).sum();
    }

    /** Asks 47 x 10 and 48 x 5, with our ask of 20 at 48 behind the 5. */
    private static MbpBook withOurAskOnTheSecondLevel(TradePairing pairing, boolean sweepingPrints) {
        MbpBook book = new MbpBook(new RiskAverseQueueModel(), pairing, sweepingPrints);
        book.onMarketUpdate(List.of(new BidAskLevel(45, 100, 47, 10), new BidAskLevel(44, 100, 48, 5)), 10, 0);
        book.addLocalOrder(order(Side.Ask, 48, 20, 1L));
        return book;
    }

    /** The trade's book change: 47 leaves the book. */
    private static void emptyTheTopAsk(MbpBook book, long venueNanos) {
        book.onMarketUpdate(List.of(new BidAskLevel(45, 100, 48, 5), new BidAskLevel(44, 100, 49, 50)), 10, venueNanos);
    }

    @Nested
    class TheEmptiedLevel {

        @Test
        void aPrintWhoseBookChangeEmptiedItsLevelClaimsThatLevelAndDoesNotSpillIntoOurs() {
            MbpBook book = withOurAskOnTheSecondLevel(PM_INTL, true);
            emptyTheTopAsk(book, ms(10));

            // The taker bought the 10 at 47; the print, stamped 20 ms later, is that same 10.
            assertEquals(0, filled(book.onTrade(47, 10, BUY, ms(30))), "the 10 was the 47 level, not ours at 48");
            assertEquals(5, book.findLocalOrder(1L).phantomVolume, "the 5 ahead of us at 48 is untouched");
        }

        @Test
        void aPrintLargerThanTheEmptiedLevelTakesOnlyTheRestFromTheNextLevel() {
            MbpBook book = withOurAskOnTheSecondLevel(PM_INTL, true);
            emptyTheTopAsk(book, ms(10));

            // 18 bought: the 10 at 47, then the 5 ahead of us at 48, then 3 of ours.
            assertEquals(3, filled(book.onTrade(47, 18, BUY, ms(30))));
        }

        @Test
        void onceNoTradeCanPairWithItTheEmptiedLevelIsForgotten() {
            MbpBook book = withOurAskOnTheSecondLevel(PM_INTL, true);
            emptyTheTopAsk(book, ms(10));

            // Far past the pairing bounds, the emptying was a cancel; a print of 15 now goes to the 5 ahead and 10 of
            // ours.
            assertEquals(10, filled(book.onTrade(47, 15, BUY, ms(400))));
        }

        @Test
        void aLevelShownAgainCarriesTheShrinkSoTheTradeStillClaimsIt() {
            MbpBook book = withOurAskOnTheSecondLevel(PM_INTL, true);
            emptyTheTopAsk(book, ms(10));
            // New sellers at 47 before the print arrives.
            book.onMarketUpdate(List.of(new BidAskLevel(45, 100, 47, 3), new BidAskLevel(44, 100, 48, 5)), 10, ms(20));

            // 13 bought: the 10 the book change already took at 47, then the 3 now there; nothing reaches 48.
            assertEquals(0, filled(book.onTrade(47, 13, BUY, ms(30))));
            assertEquals(5, book.findLocalOrder(1L).phantomVolume);
        }

        @Test
        void anOrderJoiningTheEmptiedPriceIsBehindTheTradeThatEmptiedIt() {
            MbpBook book = withOurAskOnTheSecondLevel(PM_INTL, true);
            emptyTheTopAsk(book, ms(10));
            book.addLocalOrder(order(Side.Ask, 47, 4, 2L));

            assertEquals(0, filled(book.onTrade(47, 10, BUY, ms(30))), "the 10 traded before our 47 order existed");
            assertEquals(4, book.findLocalOrder(2L).remaining);
        }
    }

    @Nested
    class Unchanged {

        @Test
        void aBookThatDoesNotPairReadsTheEmptyingAsCancelsAsBefore() {
            MbpBook book = withOurAskOnTheSecondLevel(TradePairing.OFF, true);
            book.onMarketUpdate(List.of(new BidAskLevel(45, 100, 48, 5), new BidAskLevel(44, 100, 49, 50)), 10);

            assertEquals(5, filled(book.onTrade(47, 10, BUY)), "the 10 goes to the 5 ahead at 48 and 5 of ours");
        }

        @Test
        void aPrintThatStopsAtItsPriceNeverReachedTheNextLevelEither() {
            MbpBook book = withOurAskOnTheSecondLevel(PM_INTL, false);
            emptyTheTopAsk(book, ms(10));

            assertEquals(0, filled(book.onTrade(47, 18, BUY, ms(30))));
            assertEquals(5, book.findLocalOrder(1L).phantomVolume);
        }
    }
}
