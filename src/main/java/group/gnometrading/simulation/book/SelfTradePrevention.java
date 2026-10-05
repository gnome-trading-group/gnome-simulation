package group.gnometrading.simulation.book;

/** What a venue does when an incoming order would trade against a resting order from the same participant. */
public enum SelfTradePrevention {
    /** Fills up to that point stand; the incoming order's remainder is cancelled. Kalshi's {@code taker_at_cross}. */
    CANCEL_INCOMING,
    /** The resting order is cancelled and matching continues. Kalshi's {@code maker}. */
    CANCEL_RESTING
}
