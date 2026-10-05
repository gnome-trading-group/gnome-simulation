package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPMarketDataTest.makeMbp1Update;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeCancel;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeMarketOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makePostOnlyLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeSingleLevelUpdate;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.latency.LatencyModel;
import group.gnometrading.simulation.latency.MakerTakerLatencyModel;
import group.gnometrading.simulation.latency.StaticLatency;
import group.gnometrading.simulation.queues.OptimisticQueueModel;
import group.gnometrading.simulation.queues.QueueModel;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How consumed liquidity, queue position, self-trade prevention, visibility and delays combine. */
class MbpExchangeInteractionTest {

    private static MbpSimulatedExchange exchange(QueueModel queueModel, SelfTradePrevention rule) {
        return exchange(queueModel, rule, new StaticLatency(0));
    }

    private static MbpSimulatedExchange exchange(
            QueueModel queueModel, SelfTradePrevention rule, LatencyModel orderProcessing) {
        return new MbpSimulatedExchange(
                (price, quantity, isMaker) -> 0, new StaticLatency(0), orderProcessing, queueModel, rule);
    }

    private static long filled(List<OrderExecutionReport> reports) {
        return reports.stream()
                .filter(r -> r.decoder.execType() == ExecType.FILL || r.decoder.execType() == ExecType.PARTIAL_FILL)
                .mapToLong(r -> r.decoder.filledQty())
                .sum();
    }

    private static Order ioc(long price, long size, long oid) {
        return makeOrder(price, size, Side.Bid, oid, OrderType.LIMIT, TimeInForce.IMMEDIATE_OR_CANCELED);
    }

    @Test
    void ourOwnTakingMovesOurRestingOrderUpTheQueue() {
        MbpSimulatedExchange ex = exchange(new RiskAverseQueueModel(), SelfTradePrevention.CANCEL_INCOMING);
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 5));
        Immediate.submit(ex, makeLimitOrder(100, 2, Side.Ask, 1L));
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 10));

        // Only the 5 that were ahead of our ask can be taken before a buy reaches it.
        assertEquals(3, filled(Immediate.submit(ex, ioc(100, 3, 2L))));
        List<OrderExecutionReport> second = Immediate.submit(ex, ioc(100, 3, 3L));
        assertEquals(2, filled(second));
        assertEquals(ExecType.CANCEL, second.get(second.size() - 1).decoder.execType());
        assertEquals(0, filled(Immediate.submit(ex, ioc(100, 3, 4L))));

        // Our ask is now at the front: the next market trade fills it.
        assertEquals(2, filled(ex.onMarketData(makeTrade(Side.Bid, 100, 2))));
    }

    @Test
    void tradesClearOurTakingTheSameWhetherOrNotWeHaveOtherOrders() {
        long withoutOtherOrders = takeAfterTradeAndShrink(false);
        long withAnUnrelatedBid = takeAfterTradeAndShrink(true);

        // Took 4 of 10, a trade took 3 more: 3 are left either way.
        assertEquals(3, withoutOtherOrders);
        assertEquals(3, withAnUnrelatedBid);
    }

    private static long takeAfterTradeAndShrink(boolean unrelatedBid) {
        MbpSimulatedExchange ex = exchange(new OptimisticQueueModel(), SelfTradePrevention.CANCEL_INCOMING);
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 10));
        if (unrelatedBid) {
            Immediate.submit(ex, makeLimitOrder(90, 5, Side.Bid, 99L));
        }
        Immediate.submit(ex, ioc(100, 4, 1L));
        ex.onMarketData(makeTrade(Side.Bid, 100, 3));
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 7));
        return filled(Immediate.submit(ex, ioc(100, 10, 2L)));
    }

    @Test
    void levelSweptByTheMarketForgetsWhatWeTook() {
        MbpSimulatedExchange ex = exchange(new RiskAverseQueueModel(), SelfTradePrevention.CANCEL_INCOMING);
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 10));
        Immediate.submit(ex, makeLimitOrder(90, 5, Side.Bid, 99L));
        Immediate.submit(ex, ioc(100, 10, 1L));
        ex.onMarketData(makeTrade(Side.Bid, 100, 10));
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 20));

        assertEquals(20, filled(Immediate.submit(ex, ioc(100, 20, 2L))));
    }

    @Test
    void staleOutOfViewLevelIsNotOfferedEvenWhenOurOrderRestsThere() {
        MbpSimulatedExchange ex = exchange(new RiskAverseQueueModel(), SelfTradePrevention.CANCEL_INCOMING);
        ex.onMarketData(makeMbp1Update(99, 10, 101, 50));
        Immediate.submit(ex, makeLimitOrder(101, 5, Side.Ask, 1L));
        // The best ask improves to 100: 101 is out of view and its 50 is stale.
        ex.onMarketData(makeMbp1Update(99, 10, 100, 10));

        List<OrderExecutionReport> reports = Immediate.submit(ex, makeMarketOrder(30, Side.Bid, 2L));

        assertEquals(10, filled(reports));
    }

    @Test
    void orderJoiningALevelWeTookFromIsNotBehindWhatWeTook() {
        MbpSimulatedExchange ex = exchange(new RiskAverseQueueModel(), SelfTradePrevention.CANCEL_INCOMING);
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 10));
        Immediate.submit(ex, makeOrder(98, 10, Side.Ask, 1L, OrderType.LIMIT, TimeInForce.IMMEDIATE_OR_CANCELED));
        Immediate.submit(ex, makeLimitOrder(98, 5, Side.Bid, 2L));

        assertEquals(5, filled(ex.onMarketData(makeTrade(Side.Ask, 98, 5))));
    }

    @Test
    void fillOrKillThatCannotFillLeavesOurRestingOrderAlone() {
        MbpSimulatedExchange ex = exchange(new RiskAverseQueueModel(), SelfTradePrevention.CANCEL_RESTING);
        ex.onMarketData(makeSingleLevelUpdate(99, 10, 102, 10));
        Immediate.submit(ex, makeLimitOrder(101, 5, Side.Ask, 1L));

        List<OrderExecutionReport> reports =
                Immediate.submit(ex, makeOrder(101, 5, Side.Bid, 2L, OrderType.LIMIT, TimeInForce.FILL_OR_KILL));

        assertEquals(1, reports.size());
        assertEquals(ExecType.REJECT, reports.get(0).decoder.execType());
        assertEquals(
                ExecType.CANCEL,
                Immediate.cancel(ex, makeCancel(1, 1, 1L)).get(0).decoder.execType());
    }

    @Test
    void postOnlyThatWouldCrossIsRejectedAfterTheMakerDelayNotTheTakerDelay() {
        MbpSimulatedExchange ex = exchange(
                new RiskAverseQueueModel(),
                SelfTradePrevention.CANCEL_INCOMING,
                new MakerTakerLatencyModel(0, 250, 10));
        ex.onMarketData(makeSingleLevelUpdate(99, 10, 101, 10));

        assertTrue(
                ex.submitOrder(makePostOnlyLimitOrder(101, 5, Side.Bid, 1L), 0).isEmpty());

        assertEquals(10, ex.nextDueNanos());
        assertEquals(ExecType.REJECT, ex.processDue(10).get(0).decoder.execType());
    }
}
