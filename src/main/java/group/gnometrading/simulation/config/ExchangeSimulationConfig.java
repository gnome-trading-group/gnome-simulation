package group.gnometrading.simulation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.book.TradePairing;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.exchange.SimulatedExchange;
import group.gnometrading.simulation.latency.LatencySeeds;
import group.gnometrading.sm.fee.FeeTimeline;
import java.util.function.IntFunction;

/**
 * Everything one listing's simulated exchange is built from, resolved by {@link SimulationResolver}. Market data
 * latency is only needed by backtests, so a paper session may resolve without it. {@code tradePairing} comes from the
 * venue, not from the registry's parameters: it describes how the venue's recorded feed is ordered.
 */
public record ExchangeSimulationConfig(
        FeeModelConfig feeModel,
        LatencyConfig networkLatency,
        LatencyConfig orderProcessingLatency,
        LatencyConfig marketDataLatency,
        QueueModelConfig queueModel,
        SelfTradePrevention selfTradePrevention,
        TradePairing tradePairing) {

    /** A venue whose book changes and trades are read separately. */
    public ExchangeSimulationConfig(
            FeeModelConfig feeModel,
            LatencyConfig networkLatency,
            LatencyConfig orderProcessingLatency,
            LatencyConfig marketDataLatency,
            QueueModelConfig queueModel,
            SelfTradePrevention selfTradePrevention) {
        this(
                feeModel,
                networkLatency,
                orderProcessingLatency,
                marketDataLatency,
                queueModel,
                selfTradePrevention,
                TradePairing.OFF);
    }

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
                selfTradePrevention,
                tradePairing);
    }

    /** As a config or the registry writes it, for logs and run metadata, with the venue's trade pairing beside it. */
    public String toJson() {
        SimulationOverrides blocks = new SimulationOverrides();
        blocks.feeModel = feeModel;
        blocks.networkLatency = networkLatency;
        blocks.orderProcessingLatency = orderProcessingLatency;
        blocks.marketDataLatency = marketDataLatency;
        blocks.queueModel = queueModel;
        blocks.selfTradePrevention = selfTradePrevention;
        ObjectNode json = SimulationOverrides.JSON.valueToTree(blocks);
        ObjectNode pairing = json.putObject("trade_pairing").put("enabled", tradePairing.enabled());
        if (tradePairing.enabled()) {
            pairing.put("earlier_nanos", tradePairing.earlierNanos()).put("later_nanos", tradePairing.laterNanos());
        }
        try {
            return SimulationOverrides.JSON.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
