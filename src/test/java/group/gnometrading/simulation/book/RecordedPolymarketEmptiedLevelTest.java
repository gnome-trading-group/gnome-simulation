package group.gnometrading.simulation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Twenty seconds of a Polymarket International CS2 match (961f793d, 2026-11-17 15:58:29-15:58:49 UTC) as a live session
 * recorded it, with the session's 10 bid at 57c on our outcome. A taker bought 949.53 of the other outcome, printed
 * once at its 40c average; our book showed its effect 27 ms before the print: 61c x 147, 60c x 354.17 and
 * 59c x 431.12 gone and 58c down 17.25, which add up to the print exactly. Live, our bid at 57c did not fill.
 */
class RecordedPolymarketEmptiedLevelTest {

    private static final TradePairing PM_INTL = TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25));
    private static final long SIZE = 1_000_000L;

    private static RecordedPolymarketFeed feed;

    @BeforeAll
    static void load() {
        feed = new RecordedPolymarketFeed("961f793d-2026-11-17T155829");
    }

    @Test
    void theRecordingHasThePrintOnTheOtherOutcome() {
        assertTrue(feed.records.stream().anyMatch(r -> r.trade() && !r.ours() && r.size() == 949_534_762L));
    }

    @Test
    void thePrintClaimsTheLevelsItsBookChangeEmptiedAndLeavesOurBidUnfilled() {
        assertTrue(feed.replay(PM_INTL, true, true).isEmpty(), "the 949.53 was 61c to 58c, never 57c");
    }

    @Test
    void readWithoutPairingTheEmptiedLevelsAreCancelsAndThePrintRunsOnIntoOurBid() {
        List<long[]> fills = feed.replay(TradePairing.OFF, true, true);
        assertEquals(1, fills.size());
        assertEquals(10 * SIZE, fills.get(0)[1]);
    }
}
