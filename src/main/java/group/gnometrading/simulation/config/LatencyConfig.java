package group.gnometrading.simulation.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import group.gnometrading.simulation.latency.LatencyModel;
import group.gnometrading.simulation.latency.LogNormalLatency;
import group.gnometrading.simulation.latency.MakerTakerLatencyModel;
import group.gnometrading.simulation.latency.StaticLatency;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LatencyConfig.Static.class, name = "static"),
    @JsonSubTypes.Type(value = LatencyConfig.LogNormal.class, name = "lognormal"),
    @JsonSubTypes.Type(value = LatencyConfig.MakerTaker.class, name = "maker_taker"),
    @JsonSubTypes.Type(value = LatencyConfig.Recorded.class, name = "recorded")
})
public abstract class LatencyConfig {

    /**
     * Builds the model. A random model draws from {@code seed} unless its config pins a seed of its own; the caller
     * owns the seed so runs are reproducible on its terms.
     */
    public abstract LatencyModel toModel(long seed);

    public static final class Static extends LatencyConfig {
        public long latencyNanos;

        @Override
        public LatencyModel toModel(long seed) {
            return new StaticLatency(latencyNanos);
        }
    }

    /**
     * A floor plus a log-normal tail, set by its floor, median and 99th percentile. The defaults come from recorded
     * Polymarket and Kalshi market data with the venues' publish batching taken out.
     */
    public static final class LogNormal extends LatencyConfig {
        public long floorNanos = 5_000_000L;
        public long medianNanos = 12_000_000L;
        public long p99Nanos = 200_000_000L;
        /** Pins this model's draws. Null means the model uses the seed it is built with. */
        public Long seed;

        @Override
        public LatencyModel toModel(long seed) {
            return new LogNormalLatency(floorNanos, medianNanos, p99Nanos, this.seed != null ? this.seed : seed);
        }
    }

    public static final class MakerTaker extends LatencyConfig {
        public long baseNanos;
        public long takerDelayNanos;
        public long makerDelayNanos;

        @Override
        public LatencyModel toModel(long seed) {
            return new MakerTakerLatencyModel(baseNanos, takerDelayNanos, makerDelayNanos);
        }
    }

    /**
     * Market data arrives when the recording says it did: each record's receive time, not a model. Its model is the
     * fallback, used only for a record with no usable receive time.
     */
    public static final class Recorded extends LatencyConfig {
        public LatencyConfig fallback = staticLatency(50_000_000L);

        @Override
        public LatencyModel toModel(long seed) {
            return fallback.toModel(seed);
        }
    }

    private static Static staticLatency(long nanos) {
        Static cfg = new Static();
        cfg.latencyNanos = nanos;
        return cfg;
    }
}
