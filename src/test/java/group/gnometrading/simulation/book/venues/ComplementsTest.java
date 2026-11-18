package group.gnometrading.simulation.book.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import org.junit.jupiter.api.Test;

class ComplementsTest {

    private static Listing listing(final String exchangeCode, final String exchangeSecurityId) {
        return new Listing(1, new Exchange(1, exchangeCode, exchangeCode, "us-east-1"), null, exchangeSecurityId, "x");
    }

    @Test
    void bothOutcomesOfAPolymarketInternationalMarketShareTheirConditionAsTheKey() {
        Listing jumbo = listing("POLYMARKET_INTL", "0xabc123:111");
        Listing falcons = listing("POLYMARKET_INTL", "0xabc123:222");
        assertEquals("0xabc123", Complements.pairKey(jumbo));
        assertEquals(Complements.pairKey(jumbo), Complements.pairKey(falcons));
    }

    @Test
    void aPolymarketInternationalListingThatDoesNotNameItsConditionHasNoPair() {
        assertNull(Complements.pairKey(listing("POLYMARKET_INTL", "111")));
        assertNull(Complements.pairKey(listing("POLYMARKET_INTL", ":111")));
        assertNull(Complements.pairKey(listing("POLYMARKET_INTL", null)));
    }

    @Test
    void polymarketInternationalPrintsSweep() {
        assertTrue(Complements.printsSweep(listing("POLYMARKET_INTL", "0xabc:1")));
    }

    @Test
    void venuesThatPrintEveryTradeOnTheirOwnListingsNeitherPairNorSweep() {
        for (String code : new String[] {"POLYMARKET_US", "KALSHI", "HYPERLIQUID", "LIGHTER", "SOME_NEW_VENUE"}) {
            Listing listing = listing(code, "aec-cs2-fal-jum-2026-11-17:long");
            assertNull(Complements.pairKey(listing), code);
            assertFalse(Complements.printsSweep(listing), code);
        }
    }
}
