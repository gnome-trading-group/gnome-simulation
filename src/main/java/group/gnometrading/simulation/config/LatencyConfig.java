package group.gnometrading.simulation.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import group.gnometrading.simulation.latency.GaussianLatency;
import group.gnometrading.simulation.latency.LatencyModel;
import group.gnometrading.simulation.latency.MakerTakerLatencyModel;
import group.gnometrading.simulation.latency.StaticLatency;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = LatencyConfig.Static.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = LatencyConfig.Static.class, name = "static"),
    @JsonSubTypes.Type(value = LatencyConfig.Gaussian.class, name = "gaussian"),
    @JsonSubTypes.Type(value = LatencyConfig.MakerTaker.class, name = "maker_taker")
})
public abstract class LatencyConfig {

    /**
     * Builds the model. A random model draws from {@code seed} unless its config pins a seed of its own; the caller
     * owns the seed so runs are reproducible on its terms.
     */
    public abstract LatencyModel toModel(long seed);

    public static LatencyConfig fromMap(Map<String, String> map) {
        String model = map.getOrDefault("model", "static");
        if ("gaussian".equals(model)) {
            Gaussian cfg = new Gaussian();
            cfg.mu = Double.parseDouble(map.getOrDefault("mu", "0.0"));
            cfg.sigma = Double.parseDouble(map.getOrDefault("sigma", "0.0"));
            String seed = map.get("seed");
            cfg.seed = seed == null ? null : Long.parseLong(seed);
            return cfg;
        }
        if ("maker_taker".equals(model)) {
            MakerTaker cfg = new MakerTaker();
            cfg.baseNanos = Long.parseLong(map.getOrDefault("base.nanos", "0"));
            cfg.takerDelayNanos = Long.parseLong(map.getOrDefault("taker.delay.nanos", "0"));
            cfg.makerDelayNanos = Long.parseLong(map.getOrDefault("maker.delay.nanos", "0"));
            return cfg;
        }
        Static cfg = new Static();
        cfg.latencyNanos = Long.parseLong(map.getOrDefault("nanos", "0"));
        return cfg;
    }

    public static final class Static extends LatencyConfig {
        public long latencyNanos;

        @Override
        public LatencyModel toModel(long seed) {
            return new StaticLatency(latencyNanos);
        }
    }

    public static final class Gaussian extends LatencyConfig {
        public double mu;
        public double sigma;
        /** Pins this model's draws. Null means the model uses the seed it is built with. */
        public Long seed;

        @Override
        public LatencyModel toModel(long seed) {
            return new GaussianLatency(mu, sigma, this.seed != null ? this.seed : seed);
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
}
