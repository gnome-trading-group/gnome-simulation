package group.gnometrading.simulation.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import group.gnometrading.simulation.book.SelfTradePrevention;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Some or all of how a venue behaves in simulation. The registry records a complete set per exchange; a run overrides
 * blocks of it per exchange or per listing, and {@link SimulationResolver} lays them over each other. A null block is
 * one this layer leaves to the layer below. Blocks are replaced whole, never merged field by field, so a latency block
 * always has the fields of the one model it names.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SimulationOverrides {

    /** Snake case, as configs, the registry and the controller write it: {@code network_latency: {type: static}}. */
    public static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public FeeModelConfig feeModel;
    /** Our order connection: orders out and reports back. */
    public LatencyConfig networkLatency;

    public LatencyConfig orderProcessingLatency;
    /**
     * When market data reaches the strategy. A backtest setting: paper trading gets live market data as it arrives.
     */
    public LatencyConfig marketDataLatency;

    public QueueModelConfig queueModel;
    public SelfTradePrevention selfTradePrevention;

    public static SimulationOverrides fromJson(String json) {
        try {
            return JSON.readValue(json, SimulationOverrides.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable simulation parameters: " + json, e);
        }
    }

    /** This layer with {@code over}'s blocks in place of its own wherever {@code over} sets them. */
    public SimulationOverrides with(SimulationOverrides over) {
        if (over == null) {
            return this;
        }
        SimulationOverrides result = new SimulationOverrides();
        result.feeModel = over.feeModel != null ? over.feeModel : this.feeModel;
        result.networkLatency = over.networkLatency != null ? over.networkLatency : this.networkLatency;
        result.orderProcessingLatency =
                over.orderProcessingLatency != null ? over.orderProcessingLatency : this.orderProcessingLatency;
        result.marketDataLatency = over.marketDataLatency != null ? over.marketDataLatency : this.marketDataLatency;
        result.queueModel = over.queueModel != null ? over.queueModel : this.queueModel;
        result.selfTradePrevention =
                over.selfTradePrevention != null ? over.selfTradePrevention : this.selfTradePrevention;
        return result;
    }
}
