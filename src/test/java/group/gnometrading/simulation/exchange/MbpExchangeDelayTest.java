package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeCancel;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeMarketOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeModify;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeSingleLevelUpdate;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Side;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.latency.MakerTakerLatencyModel;
import group.gnometrading.simulation.latency.StaticLatency;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Messages are held for their processing time and only then touch the book; takers wait the taker delay. */
class MbpExchangeDelayTest {

    private static final long TAKER_DELAY = 250;
    private static final long MAKER_DELAY = 10;

    private MbpSimulatedExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = new MbpSimulatedExchange(
                (price, quantity, isMaker) -> 0,
                new StaticLatency(0),
                new MakerTakerLatencyModel(0, TAKER_DELAY, MAKER_DELAY),
                new RiskAverseQueueModel(),
                SelfTradePrevention.CANCEL_INCOMING);
        exchange.onMarketData(makeSingleLevelUpdate(100, 10, 102, 10));
    }

    @Test
    void takerMatchesTheBookAsItIsWhenItsDelayEnds() {
        assertTrue(exchange.submitOrder(makeMarketOrder(5, Side.Bid, 1L), 0).isEmpty());
        assertEquals(TAKER_DELAY, exchange.nextDueNanos());

        // The ask moves up during the delay; the order pays the new price.
        exchange.onMarketData(makeSingleLevelUpdate(100, 10, 103, 10));
        assertTrue(exchange.processDue(TAKER_DELAY - 1).isEmpty());
        List<OrderExecutionReport> reports = exchange.processDue(TAKER_DELAY);

        assertEquals(ExecType.FILL, reports.get(0).decoder.execType());
        assertEquals(103, reports.get(0).decoder.fillPrice());
    }

    @Test
    void makerWaitsOnlyTheMakerDelay() {
        exchange.submitOrder(makeLimitOrder(99, 5, Side.Bid, 1L), 0);
        assertEquals(MAKER_DELAY, exchange.nextDueNanos());
        assertEquals(
                ExecType.NEW, exchange.processDue(MAKER_DELAY).get(0).decoder.execType());
    }

    @Test
    void orderThatNoLongerCrossesWhenItsDelayEndsRests() {
        exchange.submitOrder(makeLimitOrder(102, 5, Side.Bid, 1L), 0);
        exchange.onMarketData(makeSingleLevelUpdate(100, 10, 104, 10));

        List<OrderExecutionReport> reports = exchange.processDue(TAKER_DELAY);

        assertEquals(1, reports.size());
        assertEquals(ExecType.NEW, reports.get(0).decoder.execType());
    }

    @Test
    void cancelInsideTheDelayIsRefusedAndTheOrderStillMatches() {
        exchange.submitOrder(makeLimitOrder(102, 5, Side.Bid, 1L), 0);
        exchange.cancelOrder(makeCancel(1, 1, 1L), 1);

        List<OrderExecutionReport> cancelReports = exchange.processDue(1 + MAKER_DELAY);
        assertEquals(ExecType.CANCEL_REJECT, cancelReports.get(0).decoder.execType());

        List<OrderExecutionReport> fill = exchange.processDue(TAKER_DELAY);
        assertEquals(ExecType.FILL, fill.get(0).decoder.execType());
    }

    @Test
    void modifyInsideTheDelayIsRefused() {
        exchange.submitOrder(makeLimitOrder(102, 5, Side.Bid, 1L), 0);
        exchange.modifyOrder(makeModify(1, 1, 1L, 101, 5), 1);

        assertEquals(
                ExecType.CANCEL_REJECT,
                exchange.processDue(1 + MAKER_DELAY).get(0).decoder.execType());
    }

    @Test
    void cancelAfterTheOrderRestsWorks() {
        exchange.submitOrder(makeLimitOrder(99, 5, Side.Bid, 1L), 0);
        exchange.processDue(MAKER_DELAY);
        exchange.cancelOrder(makeCancel(1, 1, 1L), 20);

        assertEquals(
                ExecType.CANCEL,
                exchange.processDue(20 + MAKER_DELAY).get(0).decoder.execType());
    }

    @Test
    void modifyAcrossTheSpreadWaitsTheTakerDelay() {
        exchange.submitOrder(makeLimitOrder(99, 5, Side.Bid, 1L), 0);
        exchange.processDue(MAKER_DELAY);

        exchange.modifyOrder(makeModify(1, 1, 1L, 102, 5), 20);

        assertEquals(20 + TAKER_DELAY, exchange.nextDueNanos());
    }

    @Test
    void messagesDueTogetherAreProcessedInArrivalOrder() {
        exchange.submitOrder(makeLimitOrder(99, 5, Side.Bid, 1L), 0);
        exchange.submitOrder(makeLimitOrder(98, 5, Side.Bid, 2L), 0);

        List<OrderExecutionReport> reports = exchange.processDue(MAKER_DELAY);

        assertEquals(1L, reports.get(0).getClientOidCounter());
        assertEquals(2L, reports.get(1).getClientOidCounter());
    }

    @Test
    void reusingTheCallersOrderObjectDoesNotChangeOrdersAlreadyResting() {
        // Paper trading hands the exchange one order object re-wrapped for every message.
        Order reused = makeLimitOrder(99, 5, Side.Bid, 1L);
        exchange.submitOrder(reused, 0);
        reused.encodeClientOid(2L, 0);
        reused.encoder.price(98);
        exchange.submitOrder(reused, 0);
        exchange.processDue(MAKER_DELAY);

        // A trade at 99 fills order 1 at its own price, not the price the reused object now holds.
        List<OrderExecutionReport> fills = exchange.onMarketData(makeTrade(Side.Ask, 99, 20));
        assertEquals(1L, fills.get(0).getClientOidCounter());
        assertEquals(99, fills.get(0).decoder.fillPrice());
    }
}
