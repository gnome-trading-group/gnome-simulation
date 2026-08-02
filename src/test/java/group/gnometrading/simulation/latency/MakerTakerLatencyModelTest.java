package group.gnometrading.simulation.latency;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MakerTakerLatencyModelTest {

    private static final long BASE = 5_000_000L; // 5ms
    private static final long TAKER_DELAY = 250_000_000L; // 250ms
    private static final long MAKER_DELAY = 0L;

    private final MakerTakerLatencyModel model = new MakerTakerLatencyModel(BASE, TAKER_DELAY, MAKER_DELAY);

    @Test
    void simulateFallbackReturnsTakerPath() {
        assertEquals(BASE + TAKER_DELAY, model.simulate());
    }

    @Test
    void simulateTakerReturnsBasePlusTakerDelay() {
        assertEquals(BASE + TAKER_DELAY, model.simulate(false));
    }

    @Test
    void simulateMakerReturnsBasePlusMakerDelay() {
        assertEquals(BASE + MAKER_DELAY, model.simulate(true));
    }

    @Test
    void takerSignificantlySlowerThanMaker() {
        assertTrue(model.simulate(false) > model.simulate(true));
    }

    @Test
    void zeroBothDelays() {
        MakerTakerLatencyModel zero = new MakerTakerLatencyModel(BASE, 0, 0);
        assertEquals(BASE, zero.simulate());
        assertEquals(BASE, zero.simulate(true));
        assertEquals(BASE, zero.simulate(false));
    }
}
