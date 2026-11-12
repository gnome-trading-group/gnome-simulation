package group.gnometrading.simulation.rewards;

/**
 * One side of a market's book as a venue scores it, best price first: everyone else's size and ours at each price, in
 * contracts. Reused for every score, so it never allocates.
 */
public final class SideLevels {

    final long[] price;
    final double[] others;
    final double[] ours;
    final boolean bid;
    int count;
    /** True when the venue's book showed every level the feed carries, so the side may run deeper than we see. */
    boolean depthFull;

    /** {@code bid} for the plain outcome's bids, best when highest; otherwise its asks, the complement's bids. */
    SideLevels(final int capacity, final boolean bid) {
        this.bid = bid;
        this.price = new long[capacity];
        this.others = new double[capacity];
        this.ours = new double[capacity];
    }

    public int count() {
        return this.count;
    }

    /** True for the plain outcome's bids; false for its asks, which are bids for the complement. */
    public boolean bid() {
        return this.bid;
    }

    public long price(final int level) {
        return this.price[level];
    }

    public double others(final int level) {
        return this.others[level];
    }

    public double ours(final int level) {
        return this.ours[level];
    }

    public boolean depthFull() {
        return this.depthFull;
    }

    void clear() {
        this.count = 0;
        this.depthFull = false;
    }

    /** Adds size at a price, keeping levels best first. */
    void add(final long levelPrice, final double othersSize, final double oursSize) {
        int at = 0;
        while (at < this.count && (this.bid ? this.price[at] > levelPrice : this.price[at] < levelPrice)) {
            at++;
        }
        if (at < this.count && this.price[at] == levelPrice) {
            this.others[at] += othersSize;
            this.ours[at] += oursSize;
            return;
        }
        for (int i = this.count; i > at; i--) {
            this.price[i] = this.price[i - 1];
            this.others[i] = this.others[i - 1];
            this.ours[i] = this.ours[i - 1];
        }
        this.price[at] = levelPrice;
        this.others[at] = othersSize;
        this.ours[at] = oursSize;
        this.count++;
    }

    /** The level at a price, or -1. */
    int levelOf(final long levelPrice) {
        for (int i = 0; i < this.count; i++) {
            if (this.price[i] == levelPrice) {
                return i;
            }
        }
        return -1;
    }
}
