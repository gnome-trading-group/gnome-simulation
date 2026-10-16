package group.gnometrading.simulation.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.fee.ScheduledFeeModel;
import group.gnometrading.sm.fee.FeeComponent;
import group.gnometrading.sm.fee.FeeKind;
import group.gnometrading.sm.fee.FeeRounding;
import group.gnometrading.sm.fee.FeeSchedule;
import group.gnometrading.sm.fee.FeeSide;
import group.gnometrading.sm.fee.FeeTimeline;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduledFeesExchangeTest {

    private static final long P = MBPMarketDataTest.P;
    private static final long S = MBPMarketDataTest.S;
    private static final long CENT = P / 100;
    private static final long JULY = 1_782_864_000_000_000_000L;
    private static final long AUGUST = 1_785_542_400_000_000_000L;

    private static FeeSchedule kalshi(double taker, double maker) {
        return new FeeSchedule(
                "USD",
                new FeeComponent[] {new FeeComponent(FeeKind.PRICE_CURVE, taker, maker, 1, FeeSide.BOTH)},
                new FeeRounding(FeeRounding.Mode.CEIL, P / 10_000, FeeRounding.Scope.ORDER_ACCUMULATOR));
    }

    private final FeeTimeline timeline =
            new FeeTimeline(new long[] {JULY, AUGUST}, new long[] {AUGUST, FeeTimeline.OPEN_ENDED}, new FeeSchedule[] {
                kalshi(0.07, 0.0175), kalshi(0.035, 0.00875)
            });

    private final MbpSimulatedExchange exchange = new MbpSimulatedExchange(
            new ScheduledFeeModel(500, timeline),
            new MBPMarketDataTest.ZeroLatency(),
            new MBPMarketDataTest.ZeroLatency(),
            new MBPMarketDataTest.DummyQueueModel());

    private static Mbp10Schema at(Mbp10Schema schema, long nanos) {
        schema.encoder.timestampEvent(nanos);
        return schema;
    }

    private List<OrderExecutionReport> buy(long price, long size, long nanos) {
        Order order =
                MBPMarketDataTest.makeOrder(price, size, Side.Bid, 1L, OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        exchange.submitOrder(order, nanos);
        return exchange.processDue(nanos);
    }

    private static OrderExecutionReport fill(List<OrderExecutionReport> reports) {
        return reports.stream()
                .filter(r -> r.decoder.filledQty() > 0)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anOrdersTakerAndMakerFillsShareItsRounding() {
        exchange.onMarketData(at(MBPMarketDataTest.makeSingleLevelUpdate(50 * CENT, 5 * S, 55 * CENT, 10 * S), JULY));

        // Takes the 10 offered at $0.55 (Kalshi rounds the balance: $0.1733) and rests 1.
        OrderExecutionReport taker = fill(buy(55 * CENT, 11 * S, JULY));
        assertEquals(Liquidity.TAKER, taker.decoder.liquidity());
        assertEquals(1733 * P / 10_000, taker.decoder.fee());

        // The resting 1 fills as a maker; the rounding the taker fill left over pays back $0.0001 of it.
        OrderExecutionReport maker =
                fill(exchange.onMarketData(at(MBPMarketDataTest.makeTrade(Side.Ask, 55 * CENT, 5 * S), JULY + 1)));
        assertEquals(Liquidity.MAKER, maker.decoder.liquidity());
        assertEquals(43 * P / 10_000, maker.decoder.fee());
    }

    @Test
    void fillsPayTheScheduleInForceWhenTheyHappen() {
        exchange.onMarketData(
                at(MBPMarketDataTest.makeSingleLevelUpdate(50 * CENT, 5 * S, 50 * CENT + CENT, 10 * S), AUGUST));

        // Half rates from August: 0.035 × 0.51 × 0.49 = $0.0087465, the balance rounded to $0.0088.
        OrderExecutionReport taker = fill(buy(51 * CENT, S, AUGUST));
        assertEquals(88 * P / 10_000, taker.decoder.fee());
    }

    @Test
    void aFillWithNoKnownFeesStopsTheRun() {
        exchange.onMarketData(at(MBPMarketDataTest.makeSingleLevelUpdate(50 * CENT, 5 * S, 55 * CENT, 10 * S), 1L));

        assertThrows(IllegalStateException.class, () -> buy(55 * CENT, S, 1L));
    }
}
