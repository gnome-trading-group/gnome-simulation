package group.gnometrading.simulation.config;

import group.gnometrading.SecurityMaster;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.SimulationParameters;
import group.gnometrading.sm.fee.FeeTimeline;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Resolves what each listing simulates with, the same way for paper sessions and backtests: its exchange's parameters
 * as the registry knew them at the run's pinned time, then the run's overrides, lowest first (per exchange, then per
 * listing). Fees default to the registry's schedules as known at the same time, so a run pinned to a time replays
 * exactly.
 */
public final class SimulationResolver {

    private final SecurityMaster securityMaster;
    private final String asOf;

    /** {@code asOf} is an ISO-8601 time: the run reads what the registry had recorded by then. */
    public SimulationResolver(SecurityMaster securityMaster, String asOf) {
        this.securityMaster = securityMaster;
        this.asOf = asOf;
    }

    public String asOf() {
        return asOf;
    }

    /** The listing's fee timeline as the registry knew it at the pinned time. */
    public IntFunction<FeeTimeline> registryFees() {
        return listingId -> securityMaster.getFeeTimeline(listingId, asOf);
    }

    /**
     * @param overrides the run's layers for this listing, lowest first; null entries are skipped
     * @param needsMarketData whether the run replays market data (a backtest), which needs its latency too
     */
    public ExchangeSimulationConfig resolve(
            Listing listing, boolean needsMarketData, SimulationOverrides... overrides) {
        SimulationParameters recorded =
                securityMaster.getSimulationParameters(listing.exchange().exchangeId(), asOf);
        SimulationOverrides resolved =
                recorded == null ? new SimulationOverrides() : SimulationOverrides.fromJson(recorded.definition());
        for (SimulationOverrides layer : overrides) {
            resolved = resolved.with(layer);
        }

        List<String> missing = missingBlocks(resolved, needsMarketData);
        if (!missing.isEmpty()) {
            String venue = listing.exchange().exchangeCode();
            throw new IllegalArgumentException("Listing " + listing.listingId() + " on " + venue
                    + " has no simulation parameters recorded as of " + asOf + " and the run doesn't set "
                    + String.join(", ", missing));
        }
        return new ExchangeSimulationConfig(
                resolved.feeModel != null ? resolved.feeModel : new FeeModelConfig.Registry(),
                resolved.networkLatency,
                resolved.orderProcessingLatency,
                resolved.marketDataLatency,
                resolved.queueModel,
                resolved.selfTradePrevention);
    }

    private static List<String> missingBlocks(SimulationOverrides resolved, boolean needsMarketData) {
        List<String> missing = new ArrayList<>();
        if (resolved.networkLatency == null) {
            missing.add("network_latency");
        }
        if (resolved.orderProcessingLatency == null) {
            missing.add("order_processing_latency");
        }
        if (needsMarketData && resolved.marketDataLatency == null) {
            missing.add("market_data_latency");
        }
        if (resolved.queueModel == null) {
            missing.add("queue_model");
        }
        if (resolved.selfTradePrevention == null) {
            missing.add("self_trade_prevention");
        }
        return missing;
    }
}
