package group.gnometrading.simulation.config;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.latency.LatencyModel;
import group.gnometrading.simulation.latency.LatencySeeds;
import java.io.UncheckedIOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SimulationOverridesTest {

    @Test
    void readsEveryModelAsConfigsWriteIt() {
        SimulationOverrides overrides = SimulationOverrides.fromJson(
                """
                {"fee_model": {"type": "parametric", "taker_fee_rate": 0.07, "maker_fee_rate": 0.02},
                 "network_latency": {"type": "lognormal", "floor_nanos": 1000, "median_nanos": 2000, "p99_nanos": 9000, "seed": 42},
                 "order_processing_latency": {"type": "maker_taker", "base_nanos": 1000, "taker_delay_nanos": 500, "maker_delay_nanos": 200},
                 "market_data_latency": {"type": "recorded", "fallback": {"type": "static", "latency_nanos": 7}},
                 "queue_model": {"type": "probabilistic", "cancel_ahead_probability": 0.7},
                 "self_trade_prevention": "CANCEL_RESTING"}""");

        FeeModelConfig.Parametric fee = assertInstanceOf(FeeModelConfig.Parametric.class, overrides.feeModel);
        assertEquals(0.02, fee.makerFeeRate);
        LatencyConfig.LogNormal network = assertInstanceOf(LatencyConfig.LogNormal.class, overrides.networkLatency);
        assertEquals(9_000L, network.p99Nanos);
        assertEquals(42L, network.seed);
        LatencyConfig.MakerTaker order =
                assertInstanceOf(LatencyConfig.MakerTaker.class, overrides.orderProcessingLatency);
        assertEquals(200L, order.makerDelayNanos);
        assertEquals(7L, overrides.marketDataLatency.toModel(1L).simulate());
        assertEquals(
                0.7,
                assertInstanceOf(QueueModelConfig.Probabilistic.class, overrides.queueModel).cancelAheadProbability);
        assertEquals(SelfTradePrevention.CANCEL_RESTING, overrides.selfTradePrevention);
    }

    @Test
    void leavesUnsetBlocksToTheLayerBelow() {
        SimulationOverrides overrides = SimulationOverrides.fromJson("{\"queue_model\": {\"type\": \"optimistic\"}}");
        assertNull(overrides.networkLatency);
        assertNull(overrides.feeModel);
        assertNull(overrides.selfTradePrevention);
    }

    // A block without a model used to become static 0 latency or a risk-averse queue, whatever was meant.
    @Test
    void aBlockMustNameAKnownModel() {
        assertThrows(
                UncheckedIOException.class,
                () -> SimulationOverrides.fromJson("{\"network_latency\": {\"latency_nanos\": 5}}"));
        assertThrows(
                UncheckedIOException.class,
                () -> SimulationOverrides.fromJson("{\"queue_model\": {\"type\": \"fifo\"}}"));
        assertThrows(
                UncheckedIOException.class,
                () -> SimulationOverrides.fromJson("{\"fee_model\": {\"type\": \"flat\"}}"));
    }

    @Test
    void withReplacesWholeBlocks() {
        SimulationOverrides base = SimulationOverrides.fromJson(
                "{\"network_latency\": {\"type\": \"lognormal\", \"floor_nanos\": 1, \"median_nanos\": 2, \"p99_nanos\": 3},"
                        + " \"queue_model\": {\"type\": \"risk_averse\"}}");
        SimulationOverrides over =
                SimulationOverrides.fromJson("{\"network_latency\": {\"type\": \"static\", \"latency_nanos\": 9}}");

        SimulationOverrides result = base.with(over);

        assertEquals(9L, assertInstanceOf(LatencyConfig.Static.class, result.networkLatency).latencyNanos);
        assertInstanceOf(QueueModelConfig.RiskAverse.class, result.queueModel);
        assertSame(base, base.with(null));
    }

    @Test
    void lognormalExplicitSeedWinsOverDerived() {
        LatencyConfig.LogNormal cfg = new LatencyConfig.LogNormal();
        cfg.seed = 42L;
        assertArrayEquals(draws(cfg.toModel(1L)), draws(cfg.toModel(2L)));
    }

    @Test
    void lognormalDerivedSeedsGiveIndependentDraws() {
        LatencyConfig.LogNormal cfg = new LatencyConfig.LogNormal();
        assertArrayEquals(draws(cfg.toModel(7L)), draws(cfg.toModel(7L)));
        assertFalse(Arrays.equals(draws(cfg.toModel(7L)), draws(cfg.toModel(8L))));
    }

    @Test
    void latencySeedsStreamsAndBasesDiffer() {
        long network = LatencySeeds.derive(5L, LatencySeeds.NETWORK_STREAM);
        long processing = LatencySeeds.derive(5L, LatencySeeds.ORDER_PROCESSING_STREAM);
        assertNotEquals(network, processing);
        assertNotEquals(network, LatencySeeds.derive(6L, LatencySeeds.NETWORK_STREAM));
        assertEquals(network, LatencySeeds.derive(5L, LatencySeeds.NETWORK_STREAM));
    }

    private static long[] draws(LatencyModel model) {
        long[] out = new long[16];
        for (int i = 0; i < out.length; i++) {
            out[i] = model.simulate();
        }
        return out;
    }
}
