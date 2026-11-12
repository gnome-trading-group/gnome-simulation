package group.gnometrading.simulation.rewards.venues.kalshi;

/**
 * Which Kalshi market a listing trades, for code that can't reach gnome-gateways. A binary market is two listings: its
 * ticker (YES) and the ticker with {@code :no}, whose book and prices are the complement. This mirrors
 * {@code KalshiVenue.subjectOf} in gnome-gateways; change both together.
 */
public final class KalshiMarkets {

    private static final String COMPLEMENT = "no";

    private KalshiMarkets() {}

    /** The market's ticker. */
    public static String marketOf(final String exchangeSecurityId) {
        final int colon = exchangeSecurityId.indexOf(':');
        return colon < 0 ? exchangeSecurityId : exchangeSecurityId.substring(0, colon);
    }

    /** True for the market's NO listing. */
    public static boolean isComplement(final String exchangeSecurityId) {
        final int colon = exchangeSecurityId.indexOf(':');
        return colon >= 0 && COMPLEMENT.equalsIgnoreCase(exchangeSecurityId.substring(colon + 1));
    }
}
