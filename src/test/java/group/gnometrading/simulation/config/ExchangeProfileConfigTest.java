package group.gnometrading.simulation.config;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.latency.LatencyModel;
import group.gnometrading.simulation.latency.LatencySeeds;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExchangeProfileConfigTest {

    @Test
    void feeModel_defaultsToStaticZero() {
        FeeModelConfig result = FeeModelConfig.fromMap(Map.of());
        assertInstanceOf(FeeModelConfig.Static.class, result);
        FeeModelConfig.Static s = (FeeModelConfig.Static) result;
        assertEquals(0.0, s.takerFee);
        assertEquals(0.0, s.makerFee);
    }

    @Test
    void feeModel_staticWithValues() {
        FeeModelConfig result = FeeModelConfig.fromMap(Map.of("model", "static", "taker", "0.001", "maker", "0.0005"));
        assertInstanceOf(FeeModelConfig.Static.class, result);
        FeeModelConfig.Static s = (FeeModelConfig.Static) result;
        assertEquals(0.001, s.takerFee);
        assertEquals(0.0005, s.makerFee);
    }

    @Test
    void feeModel_parametricWithValues() {
        FeeModelConfig result =
                FeeModelConfig.fromMap(Map.of("model", "parametric", "taker.rate", "0.07", "maker.rate", "0.02"));
        assertInstanceOf(FeeModelConfig.Parametric.class, result);
        FeeModelConfig.Parametric p = (FeeModelConfig.Parametric) result;
        assertEquals(0.07, p.takerFeeRate);
        assertEquals(0.02, p.makerFeeRate);
    }

    @Test
    void feeModel_parametricDefaults() {
        FeeModelConfig result = FeeModelConfig.fromMap(Map.of("model", "parametric"));
        assertInstanceOf(FeeModelConfig.Parametric.class, result);
        FeeModelConfig.Parametric p = (FeeModelConfig.Parametric) result;
        assertEquals(0.07, p.takerFeeRate);
        assertEquals(0.0, p.makerFeeRate);
    }

    @Test
    void latency_defaultsToStaticZero() {
        LatencyConfig result = LatencyConfig.fromMap(Map.of());
        assertInstanceOf(LatencyConfig.Static.class, result);
        assertEquals(0L, ((LatencyConfig.Static) result).latencyNanos);
    }

    @Test
    void latency_staticWithValue() {
        LatencyConfig result = LatencyConfig.fromMap(Map.of("model", "static", "nanos", "5000000"));
        assertInstanceOf(LatencyConfig.Static.class, result);
        assertEquals(5_000_000L, ((LatencyConfig.Static) result).latencyNanos);
    }

    @Test
    void latency_lognormal() {
        LatencyConfig result = LatencyConfig.fromMap(
                Map.of("model", "lognormal", "floor.nanos", "1000", "median.nanos", "2000", "p99.nanos", "9000"));
        assertInstanceOf(LatencyConfig.LogNormal.class, result);
        LatencyConfig.LogNormal cfg = (LatencyConfig.LogNormal) result;
        assertEquals(1_000L, cfg.floorNanos);
        assertEquals(2_000L, cfg.medianNanos);
        assertEquals(9_000L, cfg.p99Nanos);
        assertNull(cfg.seed);
    }

    @Test
    void latency_unknownModelIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LatencyConfig.fromMap(Map.of("model", "gaussian")));
    }

    @Test
    void latency_lognormalDefaults() {
        LatencyConfig.LogNormal cfg = (LatencyConfig.LogNormal) LatencyConfig.fromMap(Map.of("model", "lognormal"));
        assertEquals(5_000_000L, cfg.floorNanos);
        assertEquals(12_000_000L, cfg.medianNanos);
        assertEquals(200_000_000L, cfg.p99Nanos);
    }

    @Test
    void latency_lognormalExplicitSeed() {
        LatencyConfig result = LatencyConfig.fromMap(Map.of("model", "lognormal", "seed", "42"));
        assertEquals(42L, ((LatencyConfig.LogNormal) result).seed);
    }

    @Test
    void latency_lognormalExplicitSeedWinsOverDerived() {
        LatencyConfig.LogNormal cfg = new LatencyConfig.LogNormal();
        cfg.seed = 42L;
        assertArrayEquals(draws(cfg.toModel(1L)), draws(cfg.toModel(2L)));
    }

    @Test
    void latency_lognormalDerivedSeedsGiveIndependentDraws() {
        LatencyConfig.LogNormal cfg = new LatencyConfig.LogNormal();
        assertArrayEquals(draws(cfg.toModel(7L)), draws(cfg.toModel(7L)));
        assertFalse(Arrays.equals(draws(cfg.toModel(7L)), draws(cfg.toModel(8L))));
    }

    @Test
    void latency_recordedFallsBackToItsModel() {
        LatencyConfig.Recorded cfg = new LatencyConfig.Recorded();
        assertEquals(50_000_000L, cfg.toModel(1L).simulate());
    }

    @Test
    void profile_defaultsReplayMarketDataAndModelOrders() {
        ExchangeProfileConfig profile = new ExchangeProfileConfig();
        assertInstanceOf(LatencyConfig.Recorded.class, profile.marketDataLatency);
        assertInstanceOf(LatencyConfig.LogNormal.class, profile.networkLatency);
    }

    @Test
    void latencySeeds_streamsAndBasesDiffer() {
        long network = LatencySeeds.derive(5L, LatencySeeds.NETWORK_STREAM);
        long processing = LatencySeeds.derive(5L, LatencySeeds.ORDER_PROCESSING_STREAM);
        assertNotEquals(network, processing);
        assertNotEquals(network, LatencySeeds.derive(6L, LatencySeeds.NETWORK_STREAM));
        assertEquals(network, LatencySeeds.derive(5L, LatencySeeds.NETWORK_STREAM));
    }

    @Test
    void latency_makerTaker() {
        LatencyConfig result = LatencyConfig.fromMap(Map.of(
                "model", "maker_taker",
                "base.nanos", "1000",
                "taker.delay.nanos", "500",
                "maker.delay.nanos", "200"));
        assertInstanceOf(LatencyConfig.MakerTaker.class, result);
        LatencyConfig.MakerTaker mt = (LatencyConfig.MakerTaker) result;
        assertEquals(1000L, mt.baseNanos);
        assertEquals(500L, mt.takerDelayNanos);
        assertEquals(200L, mt.makerDelayNanos);
    }

    @Test
    void latency_makerTakerDefaults() {
        LatencyConfig result = LatencyConfig.fromMap(Map.of("model", "maker_taker"));
        assertInstanceOf(LatencyConfig.MakerTaker.class, result);
        LatencyConfig.MakerTaker mt = (LatencyConfig.MakerTaker) result;
        assertEquals(0L, mt.baseNanos);
        assertEquals(0L, mt.takerDelayNanos);
        assertEquals(0L, mt.makerDelayNanos);
    }

    @Test
    void queue_defaultsToRiskAverse() {
        assertInstanceOf(QueueModelConfig.RiskAverse.class, QueueModelConfig.fromMap(Map.of()));
    }

    @Test
    void queue_optimistic() {
        assertInstanceOf(QueueModelConfig.Optimistic.class, QueueModelConfig.fromMap(Map.of("model", "optimistic")));
    }

    @Test
    void queue_probabilistic() {
        QueueModelConfig result =
                QueueModelConfig.fromMap(Map.of("model", "probabilistic", "cancel.ahead.probability", "0.7"));
        assertInstanceOf(QueueModelConfig.Probabilistic.class, result);
        assertEquals(0.7, ((QueueModelConfig.Probabilistic) result).cancelAheadProbability);
    }

    @Test
    void queue_probabilisticDefault() {
        QueueModelConfig result = QueueModelConfig.fromMap(Map.of("model", "probabilistic"));
        assertInstanceOf(QueueModelConfig.Probabilistic.class, result);
        assertEquals(0.5, ((QueueModelConfig.Probabilistic) result).cancelAheadProbability);
    }

    @Test
    void exchangeProfile_emptyMapUsesDefaults() {
        ExchangeProfileConfig profile = ExchangeProfileConfig.fromMap(Map.of());
        assertInstanceOf(FeeModelConfig.Static.class, profile.feeModel);
        assertInstanceOf(LatencyConfig.Static.class, profile.networkLatency);
        assertInstanceOf(LatencyConfig.Static.class, profile.orderProcessingLatency);
        assertInstanceOf(QueueModelConfig.RiskAverse.class, profile.queueModel);
        assertEquals(SelfTradePrevention.CANCEL_INCOMING, profile.selfTradePrevention);
    }

    @Test
    void exchangeProfile_selfTradePreventionFromMap() {
        ExchangeProfileConfig profile =
                ExchangeProfileConfig.fromMap(Map.of("self.trade.prevention", "CANCEL_RESTING"));
        assertEquals(SelfTradePrevention.CANCEL_RESTING, profile.selfTradePrevention);
    }

    @Test
    void exchangeProfile_roundTrip() {
        // Keys matching gnomepy SimulationConfig.to_properties() output after stripping "simulation."
        Map<String, String> map = Map.of(
                "fee.model", "parametric",
                "fee.taker.rate", "0.07",
                "fee.maker.rate", "0.0",
                "network.latency.model", "lognormal",
                "network.latency.floor.nanos", "1000",
                "network.latency.median.nanos", "2000",
                "order.latency.model", "static",
                "order.latency.nanos", "5000000",
                "queue.model", "probabilistic",
                "queue.cancel.ahead.probability", "0.3");
        ExchangeProfileConfig profile = ExchangeProfileConfig.fromMap(map);
        assertInstanceOf(FeeModelConfig.Parametric.class, profile.feeModel);
        assertInstanceOf(LatencyConfig.LogNormal.class, profile.networkLatency);
        assertInstanceOf(LatencyConfig.Static.class, profile.orderProcessingLatency);
        assertInstanceOf(QueueModelConfig.Probabilistic.class, profile.queueModel);
        assertEquals(5_000_000L, ((LatencyConfig.Static) profile.orderProcessingLatency).latencyNanos);
        assertEquals(0.3, ((QueueModelConfig.Probabilistic) profile.queueModel).cancelAheadProbability);
        assertNotNull(profile.toSimulatedExchange(1L));
    }

    private static long[] draws(LatencyModel model) {
        long[] out = new long[16];
        for (int i = 0; i < out.length; i++) {
            out[i] = model.simulate();
        }
        return out;
    }
}
