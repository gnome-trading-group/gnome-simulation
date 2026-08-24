package group.gnometrading.simulation.config;

import group.gnometrading.resources.Properties;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.exchange.SimulatedExchange;
import java.util.HashMap;
import java.util.Map;

public final class ExchangeProfileConfig {

    public FeeModelConfig feeModel = new FeeModelConfig.Static();
    public LatencyConfig networkLatency = new LatencyConfig.Static();
    public LatencyConfig orderProcessingLatency = new LatencyConfig.Static();
    public QueueModelConfig queueModel = new QueueModelConfig.RiskAverse();

    public SimulatedExchange toSimulatedExchange() {
        return new MbpSimulatedExchange(
                feeModel.toModel(), networkLatency.toModel(), orderProcessingLatency.toModel(), queueModel.toModel());
    }

    public static ExchangeProfileConfig fromProperties(Properties properties) {
        return fromMap(properties.getPropertiesByPrefix("simulation."));
    }

    public static ExchangeProfileConfig fromMap(Map<String, String> map) {
        ExchangeProfileConfig profile = new ExchangeProfileConfig();
        profile.feeModel = FeeModelConfig.fromMap(subMap(map, "fee."));
        profile.networkLatency = LatencyConfig.fromMap(subMap(map, "network.latency."));
        profile.orderProcessingLatency = LatencyConfig.fromMap(subMap(map, "order.latency."));
        profile.queueModel = QueueModelConfig.fromMap(subMap(map, "queue."));
        return profile;
    }

    private static Map<String, String> subMap(Map<String, String> map, String prefix) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }
}
