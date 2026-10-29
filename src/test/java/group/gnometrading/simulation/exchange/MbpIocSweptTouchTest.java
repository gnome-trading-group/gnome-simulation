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
 * Paper session f1cb2ef8 (2026-10-29, Hyperliquid BTC), journal seq 334-364: the OMS sends a market buy as an IOC
 * limit at the ask it last saw (no MARKET_ORDER_SLIPPAGE, so exactly the touch). Hyperliquid's book snapshots lag its
 * trades, and by the time order 40 arrives trades have taken the whole 80063 ask the snapshot still shows. The paper
 * exchange matches nothing. It used to answer REJECT with no reason, which the strategy printed as reason=UNKNOWN (70
 * of the session's 74 take rejects); now it cancels the order unfilled, as the venue does and the live gateways report.
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
        // Journal seq 334.
        exchange.onMarketData(makeTwoLevelUpdate(
                80_062 * DOLLAR, 15_265_620, 80_063 * DOLLAR, 510, 80_061 * DOLLAR, 507_320, 80_065 * DOLLAR, 834_840));
        // Journal seq 339: order 38, a market buy, takes 130 of the 510 at 80063.
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

    /** Journal seq 349-360: buyers take all 510 at 80063, then trade at 80065; no new snapshot until seq 369. */
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
