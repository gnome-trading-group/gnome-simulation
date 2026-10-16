package group.gnometrading.simulation.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * A paper session's simulation settings: its config's {@code simulation} value, which reaches the instance as JSON in
 * the shape a backtest config takes.
 *
 * <pre>
 * {"as_of": "2026-10-16T02:30:00Z",           // the registry's parameters and fees as known then (set at launch)
 *  "seed": 7,                                 // base of every listing's latency seeds; optional
 *  "exchanges": {"KALSHI": {"queue_model": {"type": "optimistic"}}},
 *  "listings": {"48212": {"network_latency": {"type": "static", "latency_nanos": 1}}}}
 * </pre>
 */
public final class SessionSimulationConfig {

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public String asOf;
    public Long seed;
    public Map<String, SimulationOverrides> exchanges = Map.of();
    public Map<Integer, SimulationOverrides> listings = Map.of();

    public static SessionSimulationConfig fromJson(String json) {
        SessionSimulationConfig config;
        try {
            config = JSON.readValue(json, SessionSimulationConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable simulation config: " + json, e);
        }
        if (config.asOf == null) {
            throw new IllegalArgumentException(
                    "simulation.as_of is not set: a paper session is launched with it pinned");
        }
        return config;
    }

    /** The listing's overrides, lowest first: its exchange's, then its own. */
    public SimulationOverrides[] layersFor(Listing listing) {
        return new SimulationOverrides[] {
            exchanges.get(listing.exchange().exchangeCode()), listings.get(listing.listingId())
        };
    }
}
