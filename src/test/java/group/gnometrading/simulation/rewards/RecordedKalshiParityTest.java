package group.gnometrading.simulation.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scorer against the research prototype that sized the Rotten Tomatoes opportunity: same recorded books, same
 * "join the best price with 500 a side" quotes, to a millionth.
 */
class RecordedKalshiParityTest {

    private static final double DOLLAR = 1e9;
    private static final double TOLERANCE = 1e-6;

    @Test
    void matchesThePrototypeOnRecordedBooks() throws IOException {
        final List<RecordedKalshiStrike> strikes = RecordedKalshiStrike.load();
        assertEquals(3, strikes.size());
        for (final RecordedKalshiStrike strike : strikes) {
            final RewardScorer scorer = strike.scorer();
            strike.replayJoining(scorer, 500);
            final RewardAccrual accrual = scorer.accrual(0);

            assertClose(strike.levelJoin500, accrual.marketGross() / DOLLAR, strike.ticker);
            final double counting = accrual.countingNanos() / (double) (strike.end - strike.start);
            assertEquals(strike.levelValidFraction, counting, TOLERANCE, strike.ticker + " counting time");
        }
    }

    private static void assertClose(final double expected, final double actual, final String what) {
        assertEquals(expected, actual, Math.max(TOLERANCE, Math.abs(expected) * TOLERANCE), what);
    }
}
