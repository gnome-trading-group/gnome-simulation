package group.gnometrading.simulation.rewards;

/** What a venue's rule made of one side: whether it qualified, its total score, and the score of our size per level. */
public final class SideScore {

    boolean qualifies;
    boolean depthUnknown;
    double total;
    final double[] ours;

    SideScore(final int capacity) {
        this.ours = new double[capacity];
    }

    /** Called by a rule before scoring a side of {@code levels} levels. */
    public void reset(final int levels) {
        this.qualifies = false;
        this.depthUnknown = false;
        this.total = 0;
        for (int i = 0; i < levels; i++) {
            this.ours[i] = 0;
        }
    }

    public void qualify(final double totalScore) {
        this.qualifies = true;
        this.total = totalScore;
    }

    /** The side fell short of its requirement, but the venue may hold more depth than the feed shows. */
    public void depthUnknown() {
        this.depthUnknown = true;
    }

    /** Our scored size at a level, after any discount. */
    public void scoreOurs(final int level, final double score) {
        this.ours[level] = score;
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
}
