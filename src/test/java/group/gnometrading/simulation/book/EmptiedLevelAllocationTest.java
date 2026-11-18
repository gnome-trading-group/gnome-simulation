package group.gnometrading.simulation.book;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Emptying a level and claiming it with the print that follows runs for every such trade, so it must not allocate. */
@Tag("allocation")
class EmptiedLevelAllocationTest {

    private static final long ALLOWED_BYTES = 16_384;
    private static final int TRADES = 20_000;
    private static final group.gnometrading.schemas.book.Side BUY = group.gnometrading.schemas.book.Side.Bid;

    private final BookSnapshot full = new BookSnapshot(4);
    private final BookSnapshot emptied = new BookSnapshot(4);
    private long nanos;

    private void replay(MbpBook book, int count) {
        for (int i = 0; i < count; i++) {
            nanos += 1_000_000L;
            book.onMarketUpdate(full, 10, nanos);
            nanos += 1_000_000L;
            book.onMarketUpdate(emptied, 10, nanos);
            nanos += 20_000_000L;
            book.onTrade(47, 10, BUY, nanos);
        }
    }

    @Test
    void emptyingALevelAndClaimingItAllocatesNothing() {
        full.add(45, 100, 47, 10);
        full.add(44, 100, 48, 5);
        emptied.add(45, 100, 48, 5);
        emptied.add(44, 100, 49, 50);
        MbpBook book = new MbpBook(
                new RiskAverseQueueModel(), TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25)), true);
        // No order of ours rests here: reconciling a level that holds our orders allocates on its own, measured
        // elsewhere.
        book.onMarketUpdate(full, 10, 0);
        replay(book, TRADES);

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        replay(book, TRADES);
        long allocated = threads.getThreadAllocatedBytes(thread) - before;

        System.out.println("#### emptied levels allocated " + allocated + "B over " + TRADES + " trades");
        assertTrue(allocated < ALLOWED_BYTES, "emptied levels allocated " + allocated + " bytes");
    }
}
