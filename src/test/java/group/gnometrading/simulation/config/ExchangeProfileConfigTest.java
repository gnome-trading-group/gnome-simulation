package group.gnometrading.simulation.config;

import static org.junit.jupiter.api.Assertions.*;

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
    void latency_gaussian() {
        LatencyConfig result = LatencyConfig.fromMap(Map.of("model", "gaussian", "mu", "100.0", "sigma", "10.0"));
        assertInstanceOf(LatencyConfig.Gaussian.class, result);
        LatencyConfig.Gaussian g = (LatencyConfig.Gaussian) result;
        assertEquals(100.0, g.mu);
        assertEquals(10.0, g.sigma);
    }

    @Test
    void latency_gaussianDefaults() {
        LatencyConfig result = LatencyConfig.fromMap(Map.of("model", "gaussian"));
        assertInstanceOf(LatencyConfig.Gaussian.class, result);
        LatencyConfig.Gaussian g = (LatencyConfig.Gaussian) result;
        assertEquals(0.0, g.mu);
        assertEquals(0.0, g.sigma);
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
    }

    @Test
    void exchangeProfile_roundTrip() {
        // Keys matching gnomepy SimulationConfig.to_properties() output after stripping "simulation."
        Map<String, String> map = Map.of(
                "fee.model", "parametric",
                "fee.taker.rate", "0.07",
                "fee.maker.rate", "0.0",
                "network.latency.model", "gaussian",
                "network.latency.mu", "100.0",
                "network.latency.sigma", "10.0",
                "order.latency.model", "static",
                "order.latency.nanos", "5000000",
                "queue.model", "probabilistic",
                "queue.cancel.ahead.probability", "0.3");
        ExchangeProfileConfig profile = ExchangeProfileConfig.fromMap(map);
        assertInstanceOf(FeeModelConfig.Parametric.class, profile.feeModel);
        assertInstanceOf(LatencyConfig.Gaussian.class, profile.networkLatency);
        assertInstanceOf(LatencyConfig.Static.class, profile.orderProcessingLatency);
        assertInstanceOf(QueueModelConfig.Probabilistic.class, profile.queueModel);
        assertEquals(5_000_000L, ((LatencyConfig.Static) profile.orderProcessingLatency).latencyNanos);
        assertEquals(0.3, ((QueueModelConfig.Probabilistic) profile.queueModel).cancelAheadProbability);
        assertNotNull(profile.toSimulatedExchange());
    }
}
