package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTwoLevelUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A mint can empty our outcome's level before its print arrives on the other outcome. The mirrored print then claims
 * that emptied level, as a print on our own outcome would, rather than running on into the bids behind it.
 */
class MbpEmptiedLevelTest {

    private static final long MILLI = 1_000_000L;
    private static final long CENT = 10_000_000L;
    private static final group.gnometrading.schemas.book.Side BUY = group.gnometrading.schemas.book.Side.Bid;

    private static SimulatedExchange exchange() {
        return new ExchangeSimulationConfig(
                        new FeeModelConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new QueueModelConfig.RiskAverse(),
                        SelfTradePrevention.CANCEL_INCOMING,
                        TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25)),
                        true)
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

    @Test
    void aMintThatAlreadyEmptiedTheTopBidDoesNotFillOurBidBelowIt() {
        SimulatedExchange a = exchange();
        // Bids 42c x 30 and 40c x 0 (ours alone), asks 44c and 45c.
        a.onMarketData(at(makeTwoLevelUpdate(42 * CENT, 30, 44 * CENT, 50, 41 * CENT, 20, 45 * CENT, 50), 0));
        Immediate.submit(a, makeLimitOrder(40 * CENT, 10, Side.Bid, 1L));

        // The mint takes the 30 at 42c: our book shows it gone 25 ms before the print on the other outcome.
        a.onMarketData(at(makeTwoLevelUpdate(41 * CENT, 20, 44 * CENT, 50, 39 * CENT, 20, 45 * CENT, 50), 25 * MILLI));
        List<OrderExecutionReport> reports = a.onComplementTrade(at(makeTrade(BUY, 58 * CENT, 30), 50 * MILLI));

        assertEquals(0, filled(reports), "the 30 bought at 58c on the other outcome was the 42c level, nothing lower");
        assertEquals(10, a.restingOrder(1L).remaining);
    }

    @Test
    void withoutTheEmptiedLevelTheSamePrintWouldHaveReachedOurBid() {
        SimulatedExchange a = exchange();
        a.onMarketData(at(makeTwoLevelUpdate(42 * CENT, 30, 44 * CENT, 50, 41 * CENT, 20, 45 * CENT, 50), 0));
        Immediate.submit(a, makeLimitOrder(40 * CENT, 10, Side.Bid, 1L));

        // The same print with no earlier book change: it takes 42c x 30 and stops; a larger one runs on to 41c and
        // ours.
        assertEquals(0, filled(a.onComplementTrade(at(makeTrade(BUY, 58 * CENT, 30), 50 * MILLI))));
        assertEquals(10, filled(a.onComplementTrade(at(makeTrade(BUY, 58 * CENT, 30), 60 * MILLI))));
    }
}
