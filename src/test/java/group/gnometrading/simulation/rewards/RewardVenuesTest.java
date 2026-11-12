package group.gnometrading.simulation.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.simulation.rewards.venues.RewardVenues;
import group.gnometrading.simulation.rewards.venues.kalshi.KalshiLiquidityRule;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import org.junit.jupiter.api.Test;

class RewardVenuesTest {

    private static Listing listing(final String exchangeCode, final String exchangeSecurityId) {
        return new Listing(1, new Exchange(1, exchangeCode, exchangeCode, "us-east-1"), null, exchangeSecurityId, "");
    }

    @Test
    void kalshiListingsShareTheirTickersMarketAndTheNoListingIsTheComplement() {
        final RewardVenues.Binding yes = RewardVenues.of(listing("KALSHI", "KXRT-VIO-60"));
        final RewardVenues.Binding no = RewardVenues.of(listing("KALSHI", "KXRT-VIO-60:no"));

        assertInstanceOf(KalshiLiquidityRule.class, yes.rule());
        assertEquals("KXRT-VIO-60", yes.marketKey());
        assertEquals("KXRT-VIO-60", no.marketKey());
        assertFalse(yes.complement());
        assertTrue(no.complement());
    }

    @Test
    void venuesWithoutASupportedProgramAreNotBound() {
        assertNull(RewardVenues.of(listing("POLYMARKET_INTL", "0xabc:123")));
        assertNull(RewardVenues.of(listing("HYPERLIQUID", "BTC")));
    }
}
