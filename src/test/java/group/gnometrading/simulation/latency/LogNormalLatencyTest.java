package group.gnometrading.simulation.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LogNormalLatencyTest {

    private static final long FLOOR = 5_000_000L;
    private static final long MEDIAN = 12_000_000L;
    private static final long P99 = 200_000_000L;

    @Test
    void drawsMatchTheConfiguredFloorMedianAndP99() {
        LogNormalLatency model = new LogNormalLatency(FLOOR, MEDIAN, P99, 11L);
        long[] draws = new long[200_000];
        for (int i = 0; i < draws.length; i++) {
            draws[i] = model.simulate();
        }
        Arrays.sort(draws);

        assertTrue(draws[0] >= FLOOR, "never faster than the floor");
        assertEquals(MEDIAN, draws[draws.length / 2], MEDIAN * 0.01, "median");
        assertEquals(P99, draws[(int) (draws.length * 0.99)], P99 * 0.05, "p99");
    }

    @Test
    void theSameSeedGivesTheSameDraws() {
        assertArrayEquals(
                draws(new LogNormalLatency(FLOOR, MEDIAN, P99, 3L)),
                draws(new LogNormalLatency(FLOOR, MEDIAN, P99, 3L)));
    }

    @Test
    void percentilesOutOfOrderAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LogNormalLatency(FLOOR, FLOOR, P99, 1L));
        assertThrows(IllegalArgumentException.class, () -> new LogNormalLatency(FLOOR, MEDIAN, MEDIAN, 1L));
        assertThrows(IllegalArgumentException.class, () -> new LogNormalLatency(-1, MEDIAN, P99, 1L));
    }

    private static long[] draws(LatencyModel model) {
        long[] out = new long[16];
        for (int i = 0; i < out.length; i++) {
            out[i] = model.simulate();
        }
        return out;
    }
}
