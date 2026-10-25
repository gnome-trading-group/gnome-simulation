package group.gnometrading.simulation.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.gnometrading.SecurityMaster;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.SimulationParameters;
import group.gnometrading.sm.fee.FeeTimeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulationResolverTest {

    private static final String AS_OF = "2026-10-01T00:00:00.000Z";
    private static final String KALSHI_PARAMETERS =
            """
            {"network_latency": {"type": "lognormal", "floor_nanos": 5000000, "median_nanos": 12000000, "p99_nanos": 200000000},
             "order_processing_latency": {"type": "maker_taker", "base_nanos": 5000000, "taker_delay_nanos": 250000000, "maker_delay_nanos": 0},
             "market_data_latency": {"type": "recorded", "fallback": {"type": "static", "latency_nanos": 50000000}},
             "queue_model": {"type": "risk_averse"},
             "self_trade_prevention": "CANCEL_INCOMING"}""";

    private final Listing kalshiListing =
            new Listing(7, new Exchange(1, "KALSHI", "Kalshi", "us-east-1"), null, "a", "A");
    private final Listing binanceListing =
            new Listing(9, new Exchange(6, "BINANCE", "Binance", "ap-northeast-1"), null, "b", "B");
    private SecurityMaster securityMaster;
    private SimulationResolver resolver;

    @BeforeEach
    void setUp() {
        securityMaster = mock(SecurityMaster.class);
        when(securityMaster.getSimulationParameters(1, AS_OF))
                .thenReturn(new SimulationParameters(12, 1, "1970-01-01T00:00:00.000Z", KALSHI_PARAMETERS));
        resolver = new SimulationResolver(securityMaster, AS_OF);
    }

    @Test
    void aListingWithNoOverridesSimulatesWithItsExchangesRecordedParameters() {
        ExchangeSimulationConfig config = resolver.resolve(kalshiListing, true);

        LatencyConfig.LogNormal network = assertInstanceOf(LatencyConfig.LogNormal.class, config.networkLatency());
        assertEquals(12_000_000L, network.medianNanos);
        LatencyConfig.MakerTaker order =
                assertInstanceOf(LatencyConfig.MakerTaker.class, config.orderProcessingLatency());
        assertEquals(250_000_000L, order.takerDelayNanos);
        assertInstanceOf(LatencyConfig.Recorded.class, config.marketDataLatency());
        assertInstanceOf(QueueModelConfig.RiskAverse.class, config.queueModel());
        assertEquals(SelfTradePrevention.CANCEL_INCOMING, config.selfTradePrevention());
        // Fees aren't simulation parameters: they come from the fee schedules unless a run overrides them.
        assertInstanceOf(FeeModelConfig.Registry.class, config.feeModel());
    }

    @Test
    void overridesReplaceWholeBlocksLowestFirst() {
        SimulationOverrides exchange = new SimulationOverrides();
        exchange.queueModel = new QueueModelConfig.Optimistic();
        LatencyConfig.Static slow = new LatencyConfig.Static();
        slow.latencyNanos = 1_000_000_000L;
        exchange.networkLatency = slow;
        SimulationOverrides listing = new SimulationOverrides();
        LatencyConfig.Static fast = new LatencyConfig.Static();
        fast.latencyNanos = 1L;
        listing.networkLatency = fast;

        ExchangeSimulationConfig config = resolver.resolve(kalshiListing, false, exchange, null, listing);

        assertSame(fast, config.networkLatency());
        assertInstanceOf(QueueModelConfig.Optimistic.class, config.queueModel());
        assertInstanceOf(LatencyConfig.MakerTaker.class, config.orderProcessingLatency());
    }

    @Test
    void anExchangeWithNothingRecordedNeedsEveryBlockFromTheRun() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> resolver.resolve(binanceListing, true));
        assertEquals(
                "Listing 9 on BINANCE has no simulation parameters recorded as of " + AS_OF
                        + " and the run doesn't set network_latency, order_processing_latency, market_data_latency,"
                        + " queue_model, self_trade_prevention",
                refused.getMessage());

        SimulationOverrides everything = SimulationOverrides.fromJson(
                """
                {"network_latency": {"type": "static", "latency_nanos": 0},
                 "order_processing_latency": {"type": "static", "latency_nanos": 0},
                 "queue_model": {"type": "risk_averse"},
                 "self_trade_prevention": "CANCEL_RESTING"}""");
        ExchangeSimulationConfig paper = resolver.resolve(binanceListing, false, everything);
        assertNull(paper.marketDataLatency());
        assertNotNull(paper.toSimulatedExchange(9, 1L, id -> FeeTimeline.EMPTY));
    }

    @Test
    void feesAreReadAsKnownAtThePinnedTime() {
        when(securityMaster.getFeeTimeline(7, AS_OF)).thenReturn(FeeTimeline.EMPTY);
        assertSame(FeeTimeline.EMPTY, resolver.registryFees().apply(7));
    }

    @Test
    void theResolvedConfigReadsBackAsTheSameParameters() {
        ExchangeSimulationConfig config = resolver.resolve(kalshiListing, true);
        String json = config.toJson();
        assertTrue(json.contains("\"fee_model\":{\"type\":\"registry\"}"), json);
        assertTrue(json.contains("\"median_nanos\":12000000"), json);
        SimulationOverrides reread = SimulationOverrides.fromJson(json);
        assertInstanceOf(LatencyConfig.Recorded.class, reread.marketDataLatency);
    }
}
