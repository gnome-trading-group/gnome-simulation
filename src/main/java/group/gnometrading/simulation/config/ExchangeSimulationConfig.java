package group.gnometrading.simulation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.exchange.SimulatedExchange;
import group.gnometrading.simulation.latency.LatencySeeds;
import group.gnometrading.sm.fee.FeeTimeline;
import java.util.function.IntFunction;

/**
 * Everything one listing's simulated exchange is built from, resolved by {@link SimulationResolver}. Market data
 * latency is only needed by backtests, so a paper session may resolve without it.
 */
public record ExchangeSimulationConfig(
        FeeModelConfig feeModel,
        LatencyConfig networkLatency,
        LatencyConfig orderProcessingLatency,
        LatencyConfig marketDataLatency,
        QueueModelConfig queueModel,
        SelfTradePrevention selfTradePrevention) {

    /**
     * Builds the exchange with its random latency models seeded from {@code seed}, each with its own stream, unless a
     * model pins its own seed. Pass a different seed per listing so listings don't share latency draws.
     * {@code registryFees} looks up a listing's fee timeline (SecurityMaster.getFeeTimeline) when the fee model needs it.
     */
    public SimulatedExchange toSimulatedExchange(int listingId, long seed, IntFunction<FeeTimeline> registryFees) {
        return new MbpSimulatedExchange(
                feeModel.toModel(listingId, registryFees),
                networkLatency.toModel(LatencySeeds.derive(seed, LatencySeeds.NETWORK_STREAM)),
                orderProcessingLatency.toModel(LatencySeeds.derive(seed, LatencySeeds.ORDER_PROCESSING_STREAM)),
                queueModel.toModel(),
                selfTradePrevention);
    }

    /** As a config or the registry writes it, for logs and run metadata. */
    public String toJson() {
        SimulationOverrides blocks = new SimulationOverrides();
        blocks.feeModel = feeModel;
        blocks.networkLatency = networkLatency;
        blocks.orderProcessingLatency = orderProcessingLatency;
        blocks.marketDataLatency = marketDataLatency;
        blocks.queueModel = queueModel;
        blocks.selfTradePrevention = selfTradePrevention;
        try {
            return SimulationOverrides.JSON.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
