package group.gnometrading.simulation.book;

import group.gnometrading.schemas.Order;

public class LocalOrder {

    public Order order;
    public long remaining;
    public long phantomVolume;
    public long cumulativeTradedQuantity;

    /** Queue ahead of this order that its level's pending shrink took off as cancels; a paired trade gives it back. */
    long pendingCancelled;
    /** Joined its level after the level's pending shrink began, so a trade pairing with that shrink came before it. */
    boolean joinedDuringShrink;
    /** Scratch for measuring what one queue-model call changed. */
    long phantomBeforeModify;

    public LocalOrder(Order order, long remaining, long phantomVolume) {
        this.order = order;
        this.remaining = remaining;
        this.phantomVolume = phantomVolume;
        this.cumulativeTradedQuantity = 0;
    }
}
