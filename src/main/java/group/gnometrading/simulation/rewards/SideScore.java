package group.gnometrading.simulation.rewards;

/**
 * What a venue's rule made of one side: whether it qualified, its total score, and the score of our size per level.
 *
 * <p>Some venues don't say which orders at the level that completes a side's requirement count: all of them, or the
 * first ones in the queue. Each score is kept under both readings: "level" (all of them) and "queue". Under the queue
 * reading the rule names that level, the size it still needed and its multiplier, and the scorer counts each of our
 * orders there by its place in the queue. A rule that names no such level scores the queue reading as the level one.
 */
public final class SideScore {

    private static final int NO_LEVEL = -1;

    boolean qualifies;
    boolean depthUnknown;
    double total;
    double totalQueue;
    final double[] ours;
    int crossing = NO_LEVEL;
    double crossingNeeded;
    double crossingMultiplier;

    SideScore(final int capacity) {
        this.ours = new double[capacity];
    }

    /** Called by a rule before scoring a side of {@code levels} levels. */
    public void reset(final int levels) {
        this.qualifies = false;
        this.depthUnknown = false;
        this.total = 0;
        this.totalQueue = 0;
        this.crossing = NO_LEVEL;
        this.crossingNeeded = 0;
        this.crossingMultiplier = 0;
        for (int i = 0; i < levels; i++) {
            this.ours[i] = 0;
        }
    }

    public void qualify(final double totalScore) {
        qualify(totalScore, totalScore);
    }

    /** The side qualified, with its total score under the level and the queue readings. */
    public void qualify(final double totalScore, final double totalQueueScore) {
        this.qualifies = true;
        this.total = totalScore;
        this.totalQueue = totalQueueScore;
    }

    /** The side fell short of its requirement, but the venue may hold more depth than the feed shows. */
    public void depthUnknown() {
        this.depthUnknown = true;
    }

    /** Our scored size at a level, after any discount. */
    public void scoreOurs(final int level, final double score) {
        this.ours[level] = score;
    }

    /**
     * Under the queue reading, the level that completed the side's requirement counts only the first {@code needed}
     * of its size in queue order, each at {@code multiplier}.
     */
    public void crossing(final int level, final double needed, final double multiplier) {
        this.crossing = level;
        this.crossingNeeded = needed;
        this.crossingMultiplier = multiplier;
    }

    public boolean qualifies() {
        return this.qualifies;
    }

    public double total() {
        return this.total;
    }

    public double ours(final int level) {
        return this.ours[level];
    }

    public double totalQueue() {
        return this.totalQueue;
    }
}
