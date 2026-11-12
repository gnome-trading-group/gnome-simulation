package group.gnometrading.simulation.rewards.venues;

import group.gnometrading.simulation.rewards.LiquidityRule;
import group.gnometrading.simulation.rewards.venues.kalshi.KalshiLiquidityRule;
import group.gnometrading.simulation.rewards.venues.kalshi.KalshiMarkets;
import group.gnometrading.sm.Listing;

/** Which venues' liquidity programs the scorer can estimate, and how each venue's listings form its markets. */
public final class RewardVenues {

    /** How a listing takes part in its venue's program: the rule, its market, and whether it is the complement. */
    public record Binding(LiquidityRule rule, String marketKey, boolean complement) {}

    private static final LiquidityRule KALSHI = new KalshiLiquidityRule();

    private RewardVenues() {}

    /** The listing's binding, or null when the scorer doesn't support its venue's program. */
    public static Binding of(final Listing listing) {
        final String id = listing.exchangeSecurityId();
        return switch (listing.exchange().exchangeCode()) {
            case "KALSHI" -> new Binding(KALSHI, KalshiMarkets.marketOf(id), KalshiMarkets.isComplement(id));
            default -> null;
        };
    }
}
