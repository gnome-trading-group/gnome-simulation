package group.gnometrading.simulation.book.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import group.gnometrading.simulation.book.TradePairing;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import org.junit.jupiter.api.Test;

class TradePairingsTest {

    private static final long MILLI = 1_000_000L;

    private static TradePairing of(final String exchangeCode) {
        return TradePairings.of(
                new Listing(1, new Exchange(1, exchangeCode, exchangeCode, "us-east-1"), null, "x", "x"));
    }

    @Test
    void kalshiPairsOnlyAtTheTradesOwnStamp() {
        assertEquals(TradePairing.SAME_TIMESTAMP, of("KALSHI"));
    }

    @Test
    void polymarketUsPairsABookChangeStampedUpToFiftyMillisecondsAfterTheTrade() {
        assertEquals(new TradePairing(true, 0, 50 * MILLI), of("POLYMARKET_US"));
    }

    @Test
    void polymarketIntlPairsABookChangeStampedUpToFiftyMillisecondsBeforeTheTrade() {
        assertEquals(new TradePairing(true, 50 * MILLI, 0), of("POLYMARKET_INTL"));
    }

    @Test
    void venuesThatSendTheTradeFirstAndUnmeasuredVenuesDoNotPair() {
        for (String code : new String[] {"HYPERLIQUID", "LIGHTER", "BINANCE_SPOT", "SOME_NEW_VENUE"}) {
            assertFalse(of(code).enabled(), code);
        }
    }
}
