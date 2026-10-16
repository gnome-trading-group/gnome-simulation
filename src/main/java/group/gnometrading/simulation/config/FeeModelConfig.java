package group.gnometrading.simulation.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import group.gnometrading.simulation.fee.FeeModel;
import group.gnometrading.simulation.fee.ParametricFeeModel;
import group.gnometrading.simulation.fee.ScheduledFeeModel;
import group.gnometrading.simulation.fee.StaticFeeModel;
import group.gnometrading.sm.fee.FeeTimeline;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * How a simulated exchange charges fees. By default each listing pays what its venue charged at the time, from the
 * registry's fee schedules; static and parametric set fixed rates instead, for parameter sweeps.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = FeeModelConfig.Registry.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = FeeModelConfig.Registry.class, name = "registry"),
    @JsonSubTypes.Type(value = FeeModelConfig.Static.class, name = "static"),
    @JsonSubTypes.Type(value = FeeModelConfig.Parametric.class, name = "parametric")
})
public abstract class FeeModelConfig {

    /** The model for one listing; {@code registryFees} looks up a listing's fee timeline when the model needs it. */
    public abstract FeeModel toModel(int listingId, IntFunction<FeeTimeline> registryFees);

    public static FeeModelConfig fromMap(Map<String, String> map) {
        String model = map.getOrDefault("model", "registry");
        if ("registry".equals(model)) {
            return new Registry();
        }
        if ("parametric".equals(model)) {
            Parametric cfg = new Parametric();
            cfg.takerFeeRate = Double.parseDouble(map.getOrDefault("taker.rate", "0.07"));
            cfg.makerFeeRate = Double.parseDouble(map.getOrDefault("maker.rate", "0.0"));
            return cfg;
        }
        if (!"static".equals(model)) {
            throw new IllegalArgumentException("Unknown fee model: " + model);
        }
        Static cfg = new Static();
        cfg.takerFee = Double.parseDouble(map.getOrDefault("taker", "0.0"));
        cfg.makerFee = Double.parseDouble(map.getOrDefault("maker", "0.0"));
        return cfg;
    }

    public static final class Registry extends FeeModelConfig {
        @Override
        public FeeModel toModel(int listingId, IntFunction<FeeTimeline> registryFees) {
            return new ScheduledFeeModel(listingId, registryFees.apply(listingId));
        }
    }

    public static final class Static extends FeeModelConfig {
        public double takerFee;
        public double makerFee;

        @Override
        public FeeModel toModel(int listingId, IntFunction<FeeTimeline> registryFees) {
            return new StaticFeeModel(takerFee, makerFee);
        }
    }

    public static final class Parametric extends FeeModelConfig {
        public double takerFeeRate = 0.07;
        public double makerFeeRate = 0.0;

        @Override
        public FeeModel toModel(int listingId, IntFunction<FeeTimeline> registryFees) {
            return new ParametricFeeModel(takerFeeRate, makerFeeRate);
        }
    }
}
