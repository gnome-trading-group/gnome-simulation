package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTwoLevelUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.latency.StaticLatency;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A market buy sent as an IOC limit at the last snapshot's ask, after trades have taken that whole level: the books
 * lag the trades (as Hyperliquid's do), so the touch the OMS priced at is gone, and the exchange matches nothing. It
 * cancels the order unfilled, as a venue does and the live gateways report, not a REJECT with no reason. Prices and
 * sizes are from a recorded Hyperliquid BTC book.
 */
class MbpIocSweptTouchTest {

    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;
    private static final long TAKE = 130;
    private static final group.gnometrading.schemas.book.Side BUYER = group.gnometrading.schemas.book.Side.Bid;
    private static final group.gnometrading.schemas.book.Side SELLER = group.gnometrading.schemas.book.Side.Ask;

    private MbpSimulatedExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = new MbpSimulatedExchange(
                (now, buy, price, quantity, maker, state) -> 0,
                new StaticLatency(0),
                new StaticLatency(0),
                new RiskAverseQueueModel(),
                SelfTradePrevention.CANCEL_INCOMING);
        // The snapshot: 510 offered at 80063.
        exchange.onMarketData(makeTwoLevelUpdate(
                80_062 * DOLLAR, 15_265_620, 80_063 * DOLLAR, 510, 80_061 * DOLLAR, 507_320, 80_065 * DOLLAR, 834_840));
        // An earlier market buy takes 130 of the 510 at 80063.
        assertEquals(ExecType.FILL, buyAtTouch(38).get(0).decoder.execType());
    }

    @Test
    void iocAtTheSnapshotTouchFillsWhileTheLevelLasts() {
        assertEquals(ExecType.FILL, buyAtTouch(40).get(0).decoder.execType());
    }

    @Test
    void iocAtATouchTradesHaveSweptIsCanceledUnfilledAsLiveReportsIt() {
        sweepTheTouch();

        OrderExecutionReport report = buyAtTouch(40).get(0);

        assertEquals(ExecType.CANCEL, report.decoder.execType());
        assertEquals(OrderStatus.CANCELED, report.decoder.orderStatus());
        assertEquals(0, report.decoder.cumulativeQty());
        assertEquals(0, report.decoder.leavesQty());
    }

    /** Buyers take what is left at 80063, then trade at 80065, all before the next snapshot. */
    private void sweepTheTouch() {
        exchange.onMarketData(makeTrade(BUYER, 80_063 * DOLLAR, 170));
        exchange.onMarketData(makeTrade(BUYER, 80_063 * DOLLAR, 170));
        exchange.onMarketData(makeTrade(BUYER, 80_063 * DOLLAR, 170));
        exchange.onMarketData(makeTrade(SELLER, 80_064 * DOLLAR, 2_800));
        exchange.onMarketData(makeTrade(BUYER, 80_065 * DOLLAR, 170));
        exchange.onMarketData(makeTrade(BUYER, 80_065 * DOLLAR, 150));
    }

    private List<OrderExecutionReport> buyAtTouch(long clientOid) {
        return Immediate.submit(
                exchange,
                makeOrder(
                        80_063 * DOLLAR,
                        TAKE,
                        Side.Bid,
                        clientOid,
                        OrderType.LIMIT,
                        TimeInForce.IMMEDIATE_OR_CANCELED));
    }
}
