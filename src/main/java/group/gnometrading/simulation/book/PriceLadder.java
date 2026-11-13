package group.gnometrading.simulation.book;

import java.util.NoSuchElementException;

/**
 * One side of a book: its levels sorted best first, with no boxing and no allocation once warm. A side holds a few
 * levels (the depth a snapshot shows, plus where our orders rest out of view), so a sorted array beats a tree. Levels
 * that leave are kept for reuse; nothing may hold a level once it is removed.
 */
public final class PriceLadder {

    private static final int INITIAL_CAPACITY = 16;

    private final boolean descending;
    private long[] prices = new long[INITIAL_CAPACITY];
    private OrderBookLevel[] levels = new OrderBookLevel[INITIAL_CAPACITY];
    private int size;
    private OrderBookLevel[] spare = new OrderBookLevel[INITIAL_CAPACITY];
    private int spareCount;

    /** @param descending true for bids (highest price first), false for asks */
    public PriceLadder(boolean descending) {
        this.descending = descending;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public long priceAt(int index) {
        return prices[index];
    }

    public OrderBookLevel levelAt(int index) {
        return levels[index];
    }

    /** The best price. */
    public long firstKey() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return prices[0];
    }

    public OrderBookLevel get(long price) {
        int index = indexOf(price);
        return index >= 0 ? levels[index] : null;
    }

    public boolean containsKey(long price) {
        return indexOf(price) >= 0;
    }

    /** The level at {@code price}, made empty if there is none yet. */
    public OrderBookLevel getOrCreate(long price) {
        int index = indexOf(price);
        if (index >= 0) {
            return levels[index];
        }
        int at = -index - 1;
        if (size == prices.length) {
            grow();
        }
        System.arraycopy(prices, at, prices, at + 1, size - at);
        System.arraycopy(levels, at, levels, at + 1, size - at);
        OrderBookLevel level = spareCount > 0 ? spare[--spareCount] : new OrderBookLevel(price, 0);
        spare[spareCount] = null;
        level.reset(price);
        prices[at] = price;
        levels[at] = level;
        size++;
        return level;
    }

    public void remove(long price) {
        int index = indexOf(price);
        if (index >= 0) {
            removeAt(index);
        }
    }

    public void removeAt(int index) {
        OrderBookLevel level = levels[index];
        System.arraycopy(prices, index + 1, prices, index, size - index - 1);
        System.arraycopy(levels, index + 1, levels, index, size - index - 1);
        size--;
        levels[size] = null;
        if (spareCount == spare.length) {
            OrderBookLevel[] larger = new OrderBookLevel[spare.length * 2];
            System.arraycopy(spare, 0, larger, 0, spareCount);
            spare = larger;
        }
        spare[spareCount++] = level;
    }

    /** The index of {@code price}, or {@code -(insertion point) - 1} if it isn't here. */
    public int indexOf(long price) {
        int low = 0;
        int high = size - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midPrice = prices[mid];
            if (midPrice == price) {
                return mid;
            }
            if (descending ? midPrice > price : midPrice < price) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return -(low + 1);
    }

    private void grow() {
        long[] largerPrices = new long[prices.length * 2];
        OrderBookLevel[] largerLevels = new OrderBookLevel[levels.length * 2];
        System.arraycopy(prices, 0, largerPrices, 0, size);
        System.arraycopy(levels, 0, largerLevels, 0, size);
        prices = largerPrices;
        levels = largerLevels;
    }
}
