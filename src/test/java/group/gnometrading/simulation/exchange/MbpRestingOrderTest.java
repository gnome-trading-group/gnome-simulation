package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeCancel;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeModify;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeSingleLevelUpdate;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.Side;
import group.gnometrading.simulation.book.LocalOrder;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.latency.StaticLatency;
import group.gnometrading.simulation.queues.OptimisticQueueModel;
import org.junit.jupiter.api.Test;

/** What the exchange says about one of our orders resting on its book, as the reward scorer reads it. */
class MbpRestingOrderTest {

    private static MbpSimulatedExchange exchange() {
        return new MbpSimulatedExchange(
                (now, buy, price, quantity, maker, state) -> 0,
                new StaticLatency(0),
                new StaticLatency(0),
                new OptimisticQueueModel(),
                SelfTradePrevention.CANCEL_INCOMING);
    }

    @Test
    void anOrderRestsAtItsPriceAndSideWithWhatIsLeftOfIt() {
        MbpSimulatedExchange ex = exchange();
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 10));
        assertNull(ex.restingOrder(1L));

        Immediate.submit(ex, makeLimitOrder(97, 5, Side.Bid, 1L));
        LocalOrder resting = ex.restingOrder(1L);
        assertEquals(97, resting.order.decoder.price());
        assertEquals(Side.Bid, resting.order.decoder.side());
        assertEquals(5, resting.remaining);
    }

    @Test
    void aModifyMovesItAndAFillShrinksIt() {
        MbpSimulatedExchange ex = exchange();
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 10));
        Immediate.submit(ex, makeLimitOrder(101, 5, Side.Ask, 1L));
        Immediate.modify(ex, makeModify(1, 1, 1L, 100, 6));
        assertEquals(100, ex.restingOrder(1L).order.decoder.price());
        assertEquals(6, ex.restingOrder(1L).remaining);

        // A buyer takes the whole level: everyone else's 10 and some or all of ours.
        ex.onMarketData(makeTrade(group.gnometrading.schemas.book.Side.Bid, 100, 12));
        LocalOrder after = ex.restingOrder(1L);
        assertTrue((after == null ? 0 : after.remaining) < 6);
    }

    @Test
    void aCancelledOrderNoLongerRests() {
        MbpSimulatedExchange ex = exchange();
        ex.onMarketData(makeSingleLevelUpdate(98, 10, 100, 10));
        Immediate.submit(ex, makeLimitOrder(97, 5, Side.Bid, 1L));
        Immediate.cancel(ex, makeCancel(1, 1, 1L));
        assertNull(ex.restingOrder(1L));
    }
}
