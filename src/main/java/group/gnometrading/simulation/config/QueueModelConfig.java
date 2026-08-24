package group.gnometrading.simulation.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import group.gnometrading.simulation.queues.OptimisticQueueModel;
import group.gnometrading.simulation.queues.ProbabilisticQueueModel;
import group.gnometrading.simulation.queues.QueueModel;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = QueueModelConfig.RiskAverse.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = QueueModelConfig.Optimistic.class, name = "optimistic"),
    @JsonSubTypes.Type(value = QueueModelConfig.RiskAverse.class, name = "risk_averse"),
    @JsonSubTypes.Type(value = QueueModelConfig.Probabilistic.class, name = "probabilistic")
})
public abstract class QueueModelConfig {

    public abstract QueueModel toModel();

    public static QueueModelConfig fromMap(Map<String, String> map) {
        String model = map.getOrDefault("model", "risk_averse");
        if ("optimistic".equals(model)) {
            return new Optimistic();
        }
        if ("probabilistic".equals(model)) {
            Probabilistic cfg = new Probabilistic();
            cfg.cancelAheadProbability = Double.parseDouble(map.getOrDefault("cancel.ahead.probability", "0.5"));
            return cfg;
        }
        return new RiskAverse();
    }

    public static final class Optimistic extends QueueModelConfig {
        @Override
        public QueueModel toModel() {
            return new OptimisticQueueModel();
        }
    }

    public static final class RiskAverse extends QueueModelConfig {
        @Override
        public QueueModel toModel() {
            return new RiskAverseQueueModel();
        }
    }

    public static final class Probabilistic extends QueueModelConfig {
        public double cancelAheadProbability = 0.5;

        @Override
        public QueueModel toModel() {
            return new ProbabilisticQueueModel(cancelAheadProbability);
        }
    }
}
