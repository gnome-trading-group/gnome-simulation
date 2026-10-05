package group.gnometrading.simulation.book;

import java.util.ArrayDeque;

public final class OrderBookLevel {

    public final long price;
    public long size;
    /** Displayed liquidity we have already taken; the replayed feed never shows our trades. */
    public long consumed;
    /** Beyond the depth the latest snapshot showed: its size is only the last one seen. */
    public boolean outOfView;

    public final ArrayDeque<LocalOrder> localOrders;

    public OrderBookLevel(long price, long size) {
        this.price = price;
        this.size = size;
        this.localOrders = new ArrayDeque<>();
    }

    /** Displayed size less what we have taken; none while out of view, since its size is stale. */
    public long available() {
        return outOfView ? 0 : Math.max(0, size - consumed);
    }

    /** Market volume an order joining the back of this level would have ahead of it. */
    public long queueOnJoin() {
        return Math.max(0, size - consumed);
    }

    /**
     * Records {@code quantity} taken from the front of this level. Our own resting orders here were behind it, so
     * they move up by as much.
     */
    public void take(long quantity) {
        consumed += quantity;
        for (LocalOrder localOrder : localOrders) {
            localOrder.phantomVolume = Math.max(0, localOrder.phantomVolume - quantity);
        }
    }

    public boolean hasLocalOrders() {
        return !localOrders.isEmpty();
    }
}
