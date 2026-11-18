package group.gnometrading.simulation.book.venues;

import group.gnometrading.simulation.book.TradePairing;
import group.gnometrading.sm.Listing;
import java.time.Duration;

/**
 * How each venue's recorded feed orders a trade against the book change it causes, and so how its simulated book
 * pairs them. Each venue is measured on its own recordings before it is listed here; every other venue, Hyperliquid
 * and Lighter included, keeps book changes and trades separate: they send the trade first, so a shrink just before a
 * trade there is an unrelated cancel.
 *
 * <p>Measured by replaying in-play hours of busy markets: where a book change that arrives before its trade is stamped
 * relative to the trade, read from shrinks of exactly the trade's size.
 */
public final class TradePairings {

    /**
     * Its book-delta and trade channels stamp a trade with the same millisecond, and about half of trade volume shows
     * on the book first. Changes stamped even a millisecond apart are never the trade's.
     */
    private static final TradePairing KALSHI = TradePairing.SAME_TIMESTAMP;

    /** The same stamp, or stamped up to about 50 ms after the trade yet delivered before it. */
    private static final TradePairing POLYMARKET_US = TradePairing.within(Duration.ZERO, Duration.ofMillis(50));

    /**
     * Mostly stamped 20-40 ms before the trade, a few just after. A window of 100 ms before and 25 ms after pairs 94% of
     * prints with their shrink while a same-sized shrink falls in it by chance about 5% of the time; widening it further
     * adds about as much chance as it pairs.
     */
    private static final TradePairing POLYMARKET_INTL =
            TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25));

    private TradePairings() {}

    public static TradePairing of(final Listing listing) {
        return switch (listing.exchange().exchangeCode()) {
            case "KALSHI" -> KALSHI;
            case "POLYMARKET_US" -> POLYMARKET_US;
            case "POLYMARKET_INTL" -> POLYMARKET_INTL;
            default -> TradePairing.OFF;
        };
    }
}
