package group.gnometrading.simulation.book;

import java.util.List;

/**
 * The levels one market-by-price record shows, each side's prices distinct, filled in place for every record so reading
 * one allocates nothing. A price shown twice on a side keeps its last size.
 */
public final class BookSnapshot {

    private static final long PRICE_NULL = Long.MIN_VALUE;

    private final long[] bidPrices;
    private final long[] bidSizes;
    private final long[] askPrices;
    private final long[] askSizes;
    private int bidCount;
    private int askCount;

    public BookSnapshot(int capacity) {
        this.bidPrices = new long[capacity];
        this.bidSizes = new long[capacity];
        this.askPrices = new long[capacity];
        this.askSizes = new long[capacity];
    }

    public static BookSnapshot of(List<BidAskLevel> levels) {
        BookSnapshot snapshot = new BookSnapshot(levels.size());
        for (BidAskLevel level : levels) {
            snapshot.add(level.bidPrice(), level.bidSize(), level.askPrice(), level.askSize());
        }
        return snapshot;
    }

    public void clear() {
        bidCount = 0;
        askCount = 0;
    }

    /** One row of the record; a side whose price is null shows nothing on that row. */
    public void add(long bidPrice, long bidSize, long askPrice, long askSize) {
        if (bidPrice != PRICE_NULL) {
            bidCount = put(bidPrices, bidSizes, bidCount, bidPrice, bidSize);
        }
        if (askPrice != PRICE_NULL) {
            askCount = put(askPrices, askSizes, askCount, askPrice, askSize);
        }
    }

    int count(boolean bid) {
        return bid ? bidCount : askCount;
    }

    long price(boolean bid, int index) {
        return bid ? bidPrices[index] : askPrices[index];
    }

    long size(boolean bid, int index) {
        return bid ? bidSizes[index] : askSizes[index];
    }

    /** The shown size at {@code price}, or 0 if the side doesn't show it. */
    long sizeAt(boolean bid, long price) {
        int index = find(bid ? bidPrices : askPrices, count(bid), price);
        return index >= 0 ? size(bid, index) : 0;
    }

    boolean shows(boolean bid, long price) {
        return find(bid ? bidPrices : askPrices, count(bid), price) >= 0;
    }

    /** The side's worst shown price: the lowest bid or the highest ask. */
    long worst(boolean bid) {
        long[] prices = bid ? bidPrices : askPrices;
        long worst = prices[0];
        for (int i = 1; i < count(bid); i++) {
            worst = bid ? Math.min(worst, prices[i]) : Math.max(worst, prices[i]);
        }
        return worst;
    }

    private static int put(long[] prices, long[] sizes, int count, long price, long size) {
        int index = find(prices, count, price);
        if (index >= 0) {
            sizes[index] = size;
            return count;
        }
        prices[count] = price;
        sizes[count] = size;
        return count + 1;
    }

    private static int find(long[] prices, int count, long price) {
        for (int i = 0; i < count; i++) {
            if (prices[i] == price) {
                return i;
            }
        }
        return -1;
    }
}
