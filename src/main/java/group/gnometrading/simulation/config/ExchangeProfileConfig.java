package group.gnometrading.simulation.config;

import group.gnometrading.resources.Properties;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.exchange.SimulatedExchange;
import group.gnometrading.simulation.latency.LatencySeeds;
import java.util.HashMap;
import java.util.Map;

public final class ExchangeProfileConfig {

    public FeeModelConfig feeModel = new FeeModelConfig.Static();
    public LatencyConfig networkLatency = new LatencyConfig.Static();
    public LatencyConfig orderProcessingLatency = new LatencyConfig.Static();
    public QueueModelConfig queueModel = new QueueModelConfig.RiskAverse();

    /**
     * Builds the exchange with its random latency models seeded from {@code seed}, each with its own stream, unless a
     * model pins its own seed. Pass a different seed per listing so listings don't share latency draws.
     */
    public SimulatedExchange toSimulatedExchange(long seed) {
        return new MbpSimulatedExchange(
                feeModel.toModel(),
                networkLatency.toModel(LatencySeeds.derive(seed, LatencySeeds.NETWORK_STREAM)),
                orderProcessingLatency.toModel(LatencySeeds.derive(seed, LatencySeeds.ORDER_PROCESSING_STREAM)),
                queueModel.toModel());
    }

    public static ExchangeProfileConfig resolveForListing(Properties properties, int listingId) {
        Map<String, String> simMap = properties.getPropertiesByPrefix("simulation.");

        String profileKey = "listing." + listingId + ".profile";
        String profileName = simMap.get(profileKey);
        if (profileName == null) {
            throw new IllegalArgumentException("No simulation profile assigned to listing " + listingId);
        }

        Map<String, String> profileMap = subMap(simMap, "profiles." + profileName + ".");
        if (profileMap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Listing " + listingId + " references profile '" + profileName + "' but it is not defined");
        }
        return fromMap(profileMap);
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
