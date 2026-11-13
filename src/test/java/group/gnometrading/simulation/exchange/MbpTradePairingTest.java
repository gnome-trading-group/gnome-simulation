package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeSingleLevelUpdate;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTwoLevelUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Side;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.book.TradePairing;
import group.gnometrading.simulation.config.ExchangeSimulationConfig;
import group.gnometrading.simulation.config.FeeModelConfig;
import group.gnometrading.simulation.config.LatencyConfig;
import group.gnometrading.simulation.config.QueueModelConfig;
import group.gnometrading.sm.fee.FeeTimeline;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The exchange pairs by each record's venue stamp, as the venue's resolved config says. */
class MbpTradePairingTest {

    private static final long MILLI = 1_000_000L;
    private static final group.gnometrading.schemas.book.Side BUY = group.gnometrading.schemas.book.Side.Bid;

    private static SimulatedExchange exchange(TradePairing pairing) {
        return new ExchangeSimulationConfig(
                        new FeeModelConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new QueueModelConfig.RiskAverse(),
                        SelfTradePrevention.CANCEL_INCOMING,
                        pairing)
                .toSimulatedExchange(1, 7L, id -> FeeTimeline.EMPTY);
    }

    private static Mbp10Schema at(Mbp10Schema record, long nanos) {
        record.encoder.timestampEvent(nanos);
        return record;
    }

    private static long filled(List<OrderExecutionReport> reports) {
        return reports.stream()
                .filter(r -> r.decoder.execType() == ExecType.FILL || r.decoder.execType() == ExecType.PARTIAL_FILL)
                .mapToLong(r -> r.decoder.filledQty())
                .sum();
    }

    /** As recorded on KXRT-HOWT-60: the 1 at 46 leaves the book, then the trade of 1 at 46, both at one millisecond. */
    private static long fillsFromTheLevelClearingTrade(SimulatedExchange exchange) {
        exchange.onMarketData(at(makeTwoLevelUpdate(40, 7, 46, 1, 39, 7, 47, 2456), 0));
        Immediate.submit(exchange, makeLimitOrder(46, 500, Side.Ask, 1L));
        exchange.onMarketData(at(makeSingleLevelUpdate(40, 7, 47, 2456), 10 * MILLI));
        return filled(exchange.onMarketData(at(makeTrade(BUY, 46, 1), 10 * MILLI)));
    }

    @Test
    void aPairingVenuesTradeThatClearedTheLevelGoesToTheMakerAheadOfUs() {
        SimulatedExchange exchange = exchange(TradePairing.SAME_TIMESTAMP);
        assertEquals(0, fillsFromTheLevelClearingTrade(exchange));
        assertEquals(1, filled(exchange.onMarketData(at(makeTrade(BUY, 46, 1), 20 * MILLI))), "we are first now");
    }

    @Test
    void aVenueThatDoesNotPairReadsTheBookChangeAsACancelAsBefore() {
        assertEquals(1, fillsFromTheLevelClearingTrade(exchange(TradePairing.OFF)));
    }

    @Test
    void theSixPartConfigKeepsBookChangesAndTradesSeparate() {
        SimulatedExchange exchange = new ExchangeSimulationConfig(
                        new FeeModelConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new QueueModelConfig.RiskAverse(),
                        SelfTradePrevention.CANCEL_INCOMING)
                .toSimulatedExchange(1, 7L, id -> FeeTimeline.EMPTY);
        assertTrue(fillsFromTheLevelClearingTrade(exchange) > 0);
    }
}
