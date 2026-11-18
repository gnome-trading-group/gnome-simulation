package group.gnometrading.simulation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Eight seconds of a Polymarket International CS2 match as a live session recorded it (FAL vs JUMBO, 2026-11-17
 * 15:38:30-15:38:38 UTC), both outcomes' books and prints, with the session's own orders on JUMBO TEAM. Live, two of
 * those bids filled (6 at 66c, then 10 at 60c), and Polymarket's own trade records show both were mints: takers bought
 * Falcons Force (90 averaging 36.9c, then 100 averaging 39.9c), each printed once on Falcons Force and never on JUMBO
 * TEAM.
 */
class RecordedPolymarketMintTest {

    private static final TradePairing PM_INTL = TradePairing.within(Duration.ofMillis(100), Duration.ofMillis(25));
    private static final long SIZE = 1_000_000L;
    private static final long CENT = 10_000_000L;

    private static RecordedPolymarketFeed feed;

    @BeforeAll
    static void load() {
        feed = new RecordedPolymarketFeed("FAL-JUMBO-2026-11-17T153830");
    }

    @Test
    void theRecordingHasBothMintsAndNoPrintOnOurOutcome() {
        long othersPrints =
                feed.records.stream().filter(r -> r.trade() && !r.ours()).count();
        long oursPrints =
                feed.records.stream().filter(r -> r.trade() && r.ours()).count();
        assertEquals(2, othersPrints);
        assertEquals(0, oursPrints);
    }

    @Test
    void mirroringThePrintsFillsOurBidsAsLiveDid() {
        List<long[]> fills = feed.replay(PM_INTL, true, true);
        assertEquals(2, fills.size());
        assertEquals(66 * CENT, fills.get(0)[0]);
        assertEquals(6 * SIZE, fills.get(0)[1]);
        assertEquals(60 * CENT, fills.get(1)[0]);
        assertEquals(10 * SIZE, fills.get(1)[1]);
    }

    @Test
    void readOneListingAtATimeTheMintsLeaveOurBidsUnfilled() {
        assertTrue(feed.replay(PM_INTL, false, true).isEmpty(), "both shrinks read as cancels");
    }
}
