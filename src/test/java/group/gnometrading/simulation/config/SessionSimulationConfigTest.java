package group.gnometrading.simulation.config;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class SessionSimulationConfigTest {

    private final Listing kalshi = new Listing(48212, new Exchange(3, "KALSHI", "Kalshi", "us-east-1"), null, "a", "A");

    @Test
    void readsThePinnedTimeSeedAndOverridesByExchangeCodeAndListing() {
        SessionSimulationConfig config = SessionSimulationConfig.fromJson(
                """
                {"as_of": "2026-10-01T00:00:00.000Z", "seed": 7,
                 "exchanges": {"KALSHI": {"queue_model": {"type": "optimistic"}}},
                 "listings": {"48212": {"network_latency": {"type": "static", "latency_nanos": 1}}}}""");

        assertEquals("2026-10-01T00:00:00.000Z", config.asOf);
        assertEquals(7L, config.seed);
        SimulationOverrides[] layers = config.layersFor(kalshi);
        assertInstanceOf(QueueModelConfig.Optimistic.class, layers[0].queueModel);
        assertNull(layers[0].networkLatency);
        assertEquals(1L, ((LatencyConfig.Static) layers[1].networkLatency).latencyNanos);
    }

    @Test
    void aListingWithoutOverridesHasNoLayers() {
        SessionSimulationConfig config = SessionSimulationConfig.fromJson("{\"as_of\": \"2026-10-01T00:00:00Z\"}");
        assertArrayEquals(new SimulationOverrides[] {null, null}, config.layersFor(kalshi));
        assertNull(config.seed);
    }

    @Test
    void needsThePinnedTime() {
        assertThrows(IllegalArgumentException.class, () -> SessionSimulationConfig.fromJson("{}"));
    }

    @Test
    void refusesWhatItDoesNotKnow() {
        assertThrows(
                UncheckedIOException.class,
                () -> SessionSimulationConfig.fromJson("{\"as_of\": \"2026-10-01T00:00:00Z\", \"profiles\": {}}"));
    }
}
