package group.gnometrading.simulation.rewards;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The scorer runs inside sessions, where the hot path must never allocate. */
@Tag("allocation")
class RewardScorerAllocationTest {

    private static final long ALLOWED_BYTES = 16_384;

    @Test
    void scoringRecordedBooksAllocatesNothing() throws IOException {
        final RecordedKalshiStrike strike = RecordedKalshiStrike.load().get(0);
        strike.replayJoining(strike.scorer(), 500);

        final RewardScorer scorer = strike.scorer();
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final long before = threads.getThreadAllocatedBytes(thread);
        strike.replayJoining(scorer, 500);
        final long allocated = threads.getThreadAllocatedBytes(thread) - before;

        System.out.println("#### scorer allocated " + allocated + "B over " + strike.timestamps.length + " books");
        assertTrue(allocated < ALLOWED_BYTES, "the scorer allocated " + allocated + " bytes");
    }
}
