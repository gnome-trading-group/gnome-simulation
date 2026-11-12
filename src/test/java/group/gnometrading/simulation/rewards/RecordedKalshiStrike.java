package group.gnometrading.simulation.rewards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import group.gnometrading.simulation.rewards.venues.kalshi.KalshiLiquidityRule;
import group.gnometrading.sm.incentive.IncentiveProgram;
import group.gnometrading.sm.incentive.IncentiveTimeline;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * One Kalshi Rotten Tomatoes strike's MBP-10 books over one liquidity program period (11-11 05:00Z to 11-12 05:00Z),
 * recorded by collection and exported with what the research prototype ({@code lip_score_v2.py}) estimated for it.
 * The books start at the last one before the period.
 */
final class RecordedKalshiStrike {

    static final int LEVELS = 10;
    private static final long CENT = 10_000_000L;
    private static final long CENTI_CENTS_TO_PRICE = 100_000L;
    private static final String RESOURCES = "/rewards/kalshi/";

    final String ticker;
    final long start;
    final long end;
    final IncentiveTimeline program;
    final double levelJoin500;
    final double levelValidFraction;
    final long[] timestamps;
    final long[][] bidPrice;
    final long[][] bidSize;
    final long[][] askPrice;
    final long[][] askSize;

    private RecordedKalshiStrike(final JsonNode expected, final List<long[]> rows) {
        this.ticker = expected.get("ticker").asText();
        this.start = nanos(expected.get("start").asText());
        this.end = nanos(expected.get("end").asText());
        this.program = new IncentiveTimeline(List.<IncentiveProgram[]>of(new IncentiveProgram[] {
            new IncentiveProgram(
                    this.ticker,
                    IncentiveProgram.IncentiveKind.LIQUIDITY,
                    this.ticker,
                    this.start,
                    this.end,
                    expected.get("periodReward").asLong() * CENTI_CENTS_TO_PRICE,
                    IncentiveProgram.RewardPer.PERIOD,
                    JsonNodeFactory.instance
                            .objectNode()
                            .put("targetSize", expected.get("targetSize").asDouble())
                            .put(
                                    "discountFactor",
                                    expected.get("discountFactor").asDouble()),
                    true,
                    this.start)
        }));
        this.levelJoin500 = expected.get("levelJoin500").asDouble();
        this.levelValidFraction = expected.get("levelValidFraction").asDouble();
        final int n = rows.size();
        this.timestamps = new long[n];
        this.bidPrice = new long[n][LEVELS];
        this.bidSize = new long[n][LEVELS];
        this.askPrice = new long[n][LEVELS];
        this.askSize = new long[n][LEVELS];
        for (int r = 0; r < n; r++) {
            final long[] row = rows.get(r);
            this.timestamps[r] = row[0];
            for (int i = 0; i < LEVELS; i++) {
                this.bidPrice[r][i] = row[1 + i];
                this.bidSize[r][i] = row[1 + LEVELS + i];
                this.askPrice[r][i] = row[1 + 2 * LEVELS + i];
                this.askSize[r][i] = row[1 + 3 * LEVELS + i];
            }
        }
    }

    private static long nanos(final String iso) {
        final Instant instant = Instant.parse(iso);
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    static List<RecordedKalshiStrike> load() throws IOException {
        final JsonNode expected;
        try (InputStream in = RecordedKalshiStrike.class.getResourceAsStream(RESOURCES + "expected.json")) {
            expected = new ObjectMapper().readTree(in);
        }
        final List<RecordedKalshiStrike> strikes = new ArrayList<>();
        for (final JsonNode strike : expected) {
            strikes.add(
                    new RecordedKalshiStrike(strike, rows(strike.get("ticker").asText())));
        }
        return strikes;
    }

    private static List<long[]> rows(final String ticker) throws IOException {
        final List<long[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(RecordedKalshiStrike.class.getResourceAsStream(RESOURCES + ticker + ".csv.gz")),
                StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] cells = line.split(",", -1);
                final long[] row = new long[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    row[i] = cells[i].isEmpty() ? 0 : Long.parseLong(cells[i]);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** A scorer with this strike's YES listing (id 1) bound. */
    RewardScorer scorer() {
        final RewardScorer scorer = new RewardScorer(RewardScorer.Mode.INSERT_OURS, 4, 8);
        scorer.bind(1, this.ticker, false, new KalshiLiquidityRule(), this.program, nanos -> CENT);
        return scorer;
    }

    /**
     * Replays the books with the prototype's "join" quotes: {@code contracts} at the best YES bid and as a NO bid at
     * the best YES ask after every book, and nothing while either side is empty.
     */
    void replayJoining(final RewardScorer scorer, final long contracts) {
        final long size = contracts * 1_000_000L;
        for (int r = 0; r < this.timestamps.length; r++) {
            final long at = this.timestamps[r];
            scorer.onBook(1, at, this.bidPrice[r], this.bidSize[r], this.askPrice[r], this.askSize[r]);
            final boolean twoSided = this.bidSize[r][0] > 0 && this.askSize[r][0] > 0;
            scorer.onOrder(1, 1, true, this.bidPrice[r][0], twoSided ? size : 0, at);
            scorer.onOrder(1, 2, false, this.askPrice[r][0], twoSided ? size : 0, at);
        }
        scorer.advanceTo(this.end);
    }
}
