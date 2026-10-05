package group.gnometrading.simulation.queues;

import group.gnometrading.simulation.book.LocalOrder;
import group.gnometrading.simulation.book.LocalOrderFill;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public interface QueueModel {

    void onModify(long previousQuantity, long newQuantity, ArrayDeque<LocalOrder> localQueue);

    /**
     * How much of a level's consumed liquidity remains after cancels removed {@code removedVolume} from it. Cancels
     * from the front of the queue were orders we already took, so they clear consumed liquidity; cancels from the
     * back leave it. Uses the same assumption about where cancels come from as {@link #onModify}.
     *
     * <p>By default cancels come from the back: consumed liquidity stays, capped at what is left.
     */
    default long consumedAfterCancels(long consumed, long removedVolume, long newQuantity) {
        return Math.min(consumed, newQuantity);
    }

    /**
     * Allocates a trade across local orders in the queue, in arrival order.
     *
     * <p>Each order's phantom volume is the market volume ahead of it, not counting our own orders. The trade first
     * uses up the market volume still ahead of an order, then fills it, then moves on; market volume used for one
     * order is also ahead of every order behind it. Once the trade cannot get past the volume ahead of an order,
     * nothing behind it fills.
     */
    default List<LocalOrderFill> onTrade(long tradeSize, ArrayDeque<LocalOrder> localQueue) {
        List<LocalOrderFill> filledOrders = new ArrayList<>();
        long left = tradeSize;
        long marketUsed = 0;
        for (LocalOrder localOrder : localQueue) {
            long marketAhead = Math.max(0, localOrder.phantomVolume - marketUsed);
            long takenFromMarket = Math.min(left, marketAhead);
            left -= takenFromMarket;
            marketUsed += takenFromMarket;
            if (left == 0) {
                break;
            }
            long filledQty = Math.min(localOrder.remaining, left);
            left -= filledQty;
            localOrder.remaining -= filledQty;
            filledOrders.add(new LocalOrderFill(localOrder, filledQty, localOrder.remaining));
        }

        for (LocalOrder localOrder : localQueue) {
            localOrder.phantomVolume = Math.max(0, localOrder.phantomVolume - marketUsed);
            localOrder.cumulativeTradedQuantity += tradeSize;
        }
        localQueue.removeIf(localOrder -> localOrder.remaining == 0);
        return filledOrders;
    }
}
