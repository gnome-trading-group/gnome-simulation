package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeLimitOrder;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTrade;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeTwoLevelUpdate;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Side;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.book.TradePairing;
import group.gnometrading.simulation.config.ExchangeSimulationConfig;
import group.gnometrading.simulation.config.FeeModelConfig;
import group.gnometrading.simulation.config.LatencyConfig;
import group.gnometrading.simulation.config.QueueModelConfig;
import group.gnometrading.sm.fee.FeeTimeline;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Paper sessions replay every print of the other outcome through this path, where the hot path must not allocate. Book
 * records are left out: the book's own reconciling is measured with the rest of the book, not here.
 */
@Tag("allocation")
class MbpComplementTradeAllocationTest {

    private static final long ALLOWED_BYTES = 16_384;
    private static final long CENT = 10_000_000L;
    private static final long MILLI = 1_000_000L;
    private static final int PRINTS = 20_000;

    private long nanos;

    private void replay(SimulatedExchange a, Mbp10Schema print, int count) {
        for (int i = 0; i < count; i++) {
            nanos += 50 * MILLI;
            print.encoder.timestampEvent(nanos);
            a.onComplementTrade(print);
        }
    }

    @Test
    void mirroringPrintsOfTheOtherOutcomeAllocatesNothing() {
        SimulatedExchange a = new ExchangeSimulationConfig(
                        new FeeModelConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new LatencyConfig.Static(),
                        new QueueModelConfig.RiskAverse(),
                        SelfTradePrevention.CANCEL_INCOMING,
                        TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25)),
                        true)
                .toSimulatedExchange(1, 7L, id -> FeeTimeline.EMPTY);
        Mbp10Schema book =
                makeTwoLevelUpdate(42 * CENT, 1_000_000, 44 * CENT, 500, 41 * CENT, 1_000_000, 45 * CENT, 500);
        a.onMarketData(book);
        Immediate.submit(a, makeLimitOrder(30 * CENT, 10, Side.Bid, 1L));
        // A buy of the other outcome at 58c is a sale against our book's 42c bids; never deep enough to reach ours.
        Mbp10Schema print = makeTrade(group.gnometrading.schemas.book.Side.Bid, 58 * CENT, 5);
        replay(a, print, PRINTS);

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        replay(a, print, PRINTS);
        long allocated = threads.getThreadAllocatedBytes(thread) - before;

        System.out.println("#### complement trades allocated " + allocated + "B over " + PRINTS + " prints");
        assertTrue(allocated < ALLOWED_BYTES, "mirroring allocated " + allocated + " bytes");
    }
}
