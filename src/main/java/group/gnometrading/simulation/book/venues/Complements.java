package group.gnometrading.simulation.book.venues;

import group.gnometrading.sm.Listing;

/**
 * Venues whose prints a simulated book can't read one listing at a time.
 *
 * <p>Polymarket International matches an order on one outcome of a market against an order on the other (a buy of
 * each mints the pair, a sell of each merges it). Each outcome's book shows the other outcome's orders folded in at
 * {@code 1 - price}, but such a trade prints only once, on the outcome the taker traded: our bid on one outcome is
 * filled by a print on the other. Every print there is a taker's whole order at its average price, so it can have
 * swept levels priced beyond the print's own price.
 *
 * <p>Measured on recorded in-play matches: the two outcomes' books mirror in more than 99% of updates; every fill of our
 * resting orders printed once, at the fill's venue time, on one of the two outcomes; about one print in seven swept past
 * its own price. Polymarket US already prints every trade on both of its listings, and Kalshi's team markets are
 * separate books, so neither is listed.
 */
public final class Complements {

    private static final char CONDITION_SEPARATOR = ':';

    private Complements() {}

    /** Whether the venue's prints are a taker's whole order at its average price, sweeping past that price. */
    public static boolean printsSweep(final Listing listing) {
        return isPolymarketIntl(listing);
    }

    /**
     * The key the listing shares with the other outcome of its market, whose prints fill its orders at
     * {@code 1 - price}, or null when the venue has no such pairing or the listing doesn't say which market it's in.
     */
    public static String pairKey(final Listing listing) {
        if (!isPolymarketIntl(listing)) {
            return null;
        }
        // The venue's id for an outcome is "<conditionId>:<tokenId>"; both outcomes of a market share the condition.
        final String id = listing.exchangeSecurityId();
        final int separator = id == null ? -1 : id.indexOf(CONDITION_SEPARATOR);
        return separator > 0 ? id.substring(0, separator) : null;
    }

    private static boolean isPolymarketIntl(final Listing listing) {
        return "POLYMARKET_INTL".equals(listing.exchange().exchangeCode());
    }
}
