package group.gnometrading.simulation.book;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.simulation.queues.QueueModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Our resting orders against a replayed market-by-price feed, each behind the market volume ahead of it in its level's
 * queue.
 *
 * <p>Snapshots and trades come from separate records. A trade record takes its volume off the levels it reaches; a
 * snapshot's shrink beyond that is read as cancels. Some venues' feeds deliver a trade's book change before the trade
 * record, which read that way would charge the trade against the queue twice; {@link TradePairing} says whether and
 * within what venue-time bounds a trade claims an earlier shrink of its level instead.
 */
public final class MbpBook {

    private static final long PRICE_NULL = Long.MIN_VALUE;

    private final QueueModel queueModel;
    private final TradePairing pairing;
    // Orders set aside while a trade claims the shrink they joined after; reused so claiming doesn't allocate.
    private final ArrayDeque<LocalOrder> joinedAfterShrink = new ArrayDeque<>();

    private final PriceLadder bids = new PriceLadder(true);
    private final PriceLadder asks = new PriceLadder(false);

    // local_orders[side][clientOidCounter] -> LocalOrder
    private final Map<Long, LocalOrder> localBidOrders = new HashMap<>();
    private final Map<Long, LocalOrder> localAskOrders = new HashMap<>();
    // The fills of the trade being applied; made only once something fills, as most trades reach none of our orders.
    private List<LocalOrderFill> tradeFills;

    public MbpBook(QueueModel queueModel) {
        this(queueModel, TradePairing.OFF);
    }

    public MbpBook(QueueModel queueModel, TradePairing pairing) {
        this.queueModel = queueModel;
        this.pairing = pairing;
    }

    public Long getBestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    public Long getBestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** {@link #onMarketUpdate(BookSnapshot, int, long)} for a book that doesn't pair, so venue time doesn't matter. */
    public List<LocalOrderFill> onMarketUpdate(List<BidAskLevel> levels, int depth) {
        requireUnpaired();
        return onMarketUpdate(BookSnapshot.of(levels), depth, 0L);
    }

    /** {@link #onMarketUpdate(BookSnapshot, int, long)} from a list of rows. */
    public List<LocalOrderFill> onMarketUpdate(List<BidAskLevel> levels, int depth, long venueNanos) {
        return onMarketUpdate(BookSnapshot.of(levels), depth, venueNanos);
    }

    /**
     * Reconciles the book against a snapshot that shows up to {@code depth} levels per side, stamped
     * {@code venueNanos} by the venue. A side showing fewer than {@code depth} levels is the whole side; otherwise
     * prices beyond its worst shown level are out of view, and our orders resting there keep their queue position until
     * the level is shown again. Returns fills if any local orders were crossed after the update.
     */
    public List<LocalOrderFill> onMarketUpdate(BookSnapshot snapshot, int depth, long venueNanos) {
        reconcileSide(bids, snapshot, true, depth, venueNanos);
        reconcileSide(asks, snapshot, false, depth, venueNanos);

        if (localBidOrders.isEmpty() && localAskOrders.isEmpty()) {
            return Collections.emptyList();
        }

        if (bids.isEmpty() || asks.isEmpty()) {
            return Collections.emptyList();
        }
        long bestBid = bids.firstKey();
        long bestAsk = asks.firstKey();
        if (bestBid < bestAsk) {
            return Collections.emptyList();
        }

        // Book is crossed — check for fills
        List<LocalOrderFill> allFills = new ArrayList<>();
        checkBidFills(bestAsk, allFills);
        checkAskFills(bestBid, allFills);

        clearFills(allFills);
        return allFills;
    }

    private void checkBidFills(long bestAsk, List<LocalOrderFill> allFills) {
        for (int b = 0; b < bids.size(); b++) {
            long bidPrice = bids.priceAt(b);
            if (bidPrice < bestAsk) {
                break;
            }
            OrderBookLevel bidLevel = bids.levelAt(b);
            if (!bidLevel.hasLocalOrders()) {
                continue;
            }
            bypassPhantom(bidLevel.localOrders);
            long remainingToFill =
                    bidLevel.localOrders.stream().mapToLong(lo -> lo.remaining).sum();
            for (int a = 0; a < asks.size(); a++) {
                long askPrice = asks.priceAt(a);
                if (askPrice > bidPrice || remainingToFill == 0) {
                    break;
                }
                OrderBookLevel askLevel = asks.levelAt(a);
                if (askLevel.available() == 0) {
                    continue;
                }
                long tradeSize = Math.min(remainingToFill, askLevel.available());
                List<LocalOrderFill> fills = queueModel.onTrade(tradeSize, bidLevel.localOrders);
                allFills.addAll(fills);
                long filledQty =
                        fills.stream().mapToLong(LocalOrderFill::fillSize).sum();
                remainingToFill -= filledQty;
                // The next snapshot will still show this liquidity; consuming it keeps it from filling us twice.
                askLevel.take(filledQty);
            }
        }
    }

    private void checkAskFills(long bestBid, List<LocalOrderFill> allFills) {
        for (int a = 0; a < asks.size(); a++) {
            long askPrice = asks.priceAt(a);
            if (askPrice > bestBid) {
                break;
            }
            OrderBookLevel askLevel = asks.levelAt(a);
            if (!askLevel.hasLocalOrders()) {
                continue;
            }
            bypassPhantom(askLevel.localOrders);
            long remainingToFill =
                    askLevel.localOrders.stream().mapToLong(lo -> lo.remaining).sum();
            for (int b = 0; b < bids.size(); b++) {
                long bidPrice = bids.priceAt(b);
                if (bidPrice < askPrice || remainingToFill == 0) {
                    break;
                }
                OrderBookLevel bidLevel = bids.levelAt(b);
                if (bidLevel.available() == 0) {
                    continue;
                }
                long tradeSize = Math.min(remainingToFill, bidLevel.available());
                List<LocalOrderFill> fills = queueModel.onTrade(tradeSize, askLevel.localOrders);
                allFills.addAll(fills);
                long filledQty =
                        fills.stream().mapToLong(LocalOrderFill::fillSize).sum();
                remainingToFill -= filledQty;
                bidLevel.take(filledQty);
            }
        }
    }

    private void bypassPhantom(ArrayDeque<LocalOrder> localOrders) {
        for (LocalOrder lo : localOrders) {
            lo.phantomVolume = 0;
        }
    }

    /** {@link #onTrade(long, long, group.gnometrading.schemas.book.Side, long)} for a book that doesn't pair. */
    public List<LocalOrderFill> onTrade(long price, long size, group.gnometrading.schemas.book.Side tradeSide) {
        requireUnpaired();
        return onTrade(price, size, tradeSide, 0L);
    }

    /**
     * Processes a trade from the market feed, stamped {@code venueNanos} by the venue, filling local orders on the
     * opposite side. At a level whose shrink it pairs with, the trade first claims that shrink: the book already took
     * that volume off, so only the queue is rebuilt. Volume beyond it reaches the level as a trade the book hasn't shown.
     */
    public List<LocalOrderFill> onTrade(
            long price, long size, group.gnometrading.schemas.book.Side tradeSide, long venueNanos) {
        // Runs even with no local orders: the trade takes market volume off each level it reaches, which is what lets a
        // later snapshot's shrink be read as cancels.
        // The trade side is the aggressor side; our local orders are on the opposite side
        boolean tradeIsBid = tradeSide == group.gnometrading.schemas.book.Side.Bid;
        PriceLadder oppBook = tradeIsBid ? asks : bids;
        tradeFills = null;

        long remainingSize = size;
        for (int i = 0; i < oppBook.size() && remainingSize > 0; i++) {
            long levelPrice = oppBook.priceAt(i);
            // For ask levels: stop if ask price > trade price (trade can't reach here)
            // For bid levels: stop if bid price < trade price
            if (tradeIsBid ? levelPrice > price : levelPrice < price) {
                break;
            }
            remainingSize = tradeAt(oppBook.levelAt(i), remainingSize, venueNanos);
        }

        List<LocalOrderFill> allFills = tradeFills;
        tradeFills = null;
        if (allFills == null) {
            return Collections.emptyList();
        }
        clearFills(allFills);
        return allFills;
    }

    /** What of a trade reaches {@code level}: a paired shrink first, then our orders, then the market's volume. */
    private long tradeAt(OrderBookLevel level, long size, long venueNanos) {
        long remainingSize = size;
        if (level.pendingShrink > 0 && pairing.pairs(level.pendingFirstNanos, level.pendingLastNanos, venueNanos)) {
            long claimed = Math.min(remainingSize, level.pendingShrink);
            tradeFills = claimShrink(level, claimed, tradeFills);
            remainingSize -= claimed;
            if (remainingSize <= 0) {
                return remainingSize;
            }
        }

        if (level.hasLocalOrders()) {
            List<LocalOrderFill> fills = queueModel.onTrade(remainingSize, level.localOrders);
            tradeFills = append(tradeFills, fills);
            for (int f = 0; f < fills.size(); f++) {
                remainingSize -= fills.get(f).fillSize();
            }
        }

        // Consume remaining market (non-local) volume at this level
        long leftToConsume = Math.min(remainingSize, level.size);
        remainingSize -= leftToConsume;
        level.size -= leftToConsume;
        // What we took can't outlast the level: once the market has swept it, nothing of it is left to block.
        level.consumed = Math.min(level.consumed, level.size);
        return remainingSize;
    }

    private static List<LocalOrderFill> append(List<LocalOrderFill> allFills, List<LocalOrderFill> fills) {
        if (fills.isEmpty()) {
            return allFills;
        }
        List<LocalOrderFill> into = allFills != null ? allFills : new ArrayList<>(fills.size());
        into.addAll(fills);
        return into;
    }

    /**
     * Places a local order into the book with phantom volume equal to the current displayed depth at the price level.
     */
    public void addLocalOrder(Order order, long remaining) {
        Side side = order.decoder.side();
        long price = order.decoder.price();
        long clientOid = order.getClientOidCounter();
        PriceLadder book = side == Side.Bid ? bids : asks;
        Map<Long, LocalOrder> localOrders = side == Side.Bid ? localBidOrders : localAskOrders;

        if (localOrders.containsKey(clientOid)) {
            throw new IllegalArgumentException("Duplicate client OID: " + clientOid);
        }

        OrderBookLevel level = book.getOrCreate(price);

        LocalOrder localOrder = new LocalOrder(order, remaining, level.queueOnJoin());
        localOrders.put(clientOid, localOrder);
        level.join(localOrder);
    }

    public void addLocalOrder(Order order) {
        addLocalOrder(order, order.decoder.size());
    }

    /**
     * Cancels a local order by clientOidCounter. Returns true if the order was found and removed.
     */
    public boolean cancelOrder(long clientOid) {
        LocalOrder localOrder = localBidOrders.remove(clientOid);
        Side side = Side.Bid;
        if (localOrder == null) {
            localOrder = localAskOrders.remove(clientOid);
            side = Side.Ask;
        }
        if (localOrder == null) {
            return false;
        }

        PriceLadder book = side == Side.Bid ? bids : asks;
        long price = localOrder.order.decoder.price();
        OrderBookLevel level = book.get(price);
        if (level != null) {
            level.localOrders.remove(localOrder);
            if (level.size == 0 && !level.hasLocalOrders()) {
                book.remove(price);
            }
        }
        return true;
    }

    /** The live local order for {@code clientOid}, on either side, or null if there is none. */
    public LocalOrder findLocalOrder(long clientOid) {
        LocalOrder localOrder = localBidOrders.get(clientOid);
        return localOrder != null ? localOrder : localAskOrders.get(clientOid);
    }

    /**
     * Replaces a local order's price and FIX order quantity, the order's total size including what has
     * already filled. What stays working is the new order quantity less the fills, so a replace at or
     * below the filled quantity is rejected, as FIX requires.
     *
     * @return false if there is no such order or the replace is rejected
     */
    public boolean modifyLocalOrder(long clientOid, long newPrice, long newOrderQty) {
        LocalOrder localOrder = findLocalOrder(clientOid);
        if (localOrder == null) {
            return false;
        }
        PriceLadder book = localBidOrders.containsKey(clientOid) ? bids : asks;
        long filledQty = localOrder.order.decoder.size() - localOrder.remaining;
        long newRemaining = newOrderQty - filledQty;
        if (newRemaining <= 0) {
            return false;
        }

        if (localOrder.order.decoder.price() != newPrice) {
            moveToPrice(book, localOrder, newPrice, newOrderQty, newRemaining);
        } else {
            resize(book, localOrder, newOrderQty, newRemaining);
        }
        return true;
    }

    /** A price change loses queue position: the order joins the back of its new level. */
    private static void moveToPrice(
            PriceLadder book, LocalOrder localOrder, long newPrice, long newOrderQty, long newRemaining) {
        long oldPrice = localOrder.order.decoder.price();
        OrderBookLevel oldLevel = book.get(oldPrice);
        if (oldLevel != null) {
            oldLevel.localOrders.remove(localOrder);
            if (oldLevel.size == 0 && !oldLevel.hasLocalOrders()) {
                book.remove(oldPrice);
            }
        }

        localOrder.order.encoder.price(newPrice).size(newOrderQty);
        localOrder.remaining = newRemaining;

        OrderBookLevel newLevel = book.getOrCreate(newPrice);
        localOrder.phantomVolume = newLevel.queueOnJoin();
        newLevel.join(localOrder);
    }

    /** A size decrease keeps its place in the queue; an increase loses priority and rejoins at the back. */
    private static void resize(PriceLadder book, LocalOrder localOrder, long newOrderQty, long newRemaining) {
        boolean increase = newRemaining > localOrder.remaining;
        localOrder.order.encoder.size(newOrderQty);
        localOrder.remaining = newRemaining;
        if (increase) {
            OrderBookLevel level = book.get(localOrder.order.decoder.price());
            level.localOrders.remove(localOrder);
            localOrder.phantomVolume = level.queueOnJoin();
            level.join(localOrder);
        }
    }

    public MatchPlan planMatches(Order order, SelfTradePrevention selfTradePrevention) {
        return planMatches(
                order.decoder.side(),
                order.decoder.orderType(),
                order.decoder.price(),
                order.decoder.size(),
                selfTradePrevention);
    }

    /**
     * Walks the opposite side of the book for {@code quantity} at {@code orderPrice}, without changing anything.
     *
     * <p>At a level holding our own resting orders, the incoming order first takes the market volume queued ahead of
     * each of them. If it still has quantity left it has reached our own order, and the self-trade rule decides:
     * stop there, or cancel that resting order and carry on through the level.
     */
    public MatchPlan planMatches(
            Side side, OrderType orderType, long orderPrice, long quantity, SelfTradePrevention selfTradePrevention) {
        List<OrderMatch> matches = new ArrayList<>();
        List<LocalOrder> restingToCancel = new ArrayList<>();
        long remaining = quantity;
        boolean isBuy = side == Side.Bid;
        PriceLadder oppBook = isBuy ? asks : bids;

        for (int i = 0; i < oppBook.size(); i++) {
            if (remaining == 0) {
                break;
            }
            long levelPrice = oppBook.priceAt(i);
            if (orderType == OrderType.LIMIT && (isBuy ? levelPrice > orderPrice : levelPrice < orderPrice)) {
                break;
            }
            OrderBookLevel level = oppBook.levelAt(i);
            long available = level.available();
            long taken = 0;
            for (LocalOrder own : level.localOrders) {
                long ahead = Math.max(0, Math.min(own.phantomVolume, available) - taken);
                long take = Math.min(remaining, ahead);
                taken += take;
                remaining -= take;
                if (remaining == 0) {
                    break;
                }
                if (selfTradePrevention == SelfTradePrevention.CANCEL_INCOMING) {
                    addMatch(matches, levelPrice, taken);
                    return new MatchPlan(matches, restingToCancel, true);
                }
                restingToCancel.add(own);
            }
            long take = Math.min(remaining, available - taken);
            taken += take;
            remaining -= take;
            addMatch(matches, levelPrice, taken);
        }

        return new MatchPlan(matches, restingToCancel, false);
    }

    /** Records liquidity an incoming order on {@code takerSide} has taken, so it is not offered again. */
    public void consume(Side takerSide, List<OrderMatch> matches) {
        PriceLadder oppBook = takerSide == Side.Bid ? asks : bids;
        for (OrderMatch match : matches) {
            OrderBookLevel level = oppBook.get(match.price());
            if (level != null) {
                level.take(match.size());
            }
        }
    }

    private static void addMatch(List<OrderMatch> matches, long price, long size) {
        if (size > 0) {
            matches.add(new OrderMatch(price, size));
        }
    }

    // --- Package-visible accessors for tests ---

    PriceLadder bids() {
        return bids;
    }

    PriceLadder asks() {
        return asks;
    }

    Map<Long, LocalOrder> localBidOrders() {
        return localBidOrders;
    }

    Map<Long, LocalOrder> localAskOrders() {
        return localAskOrders;
    }

    /**
     * Brings one side to what the snapshot shows. Each price is reconciled on its own, so the book's prices the snapshot
     * no longer shows go first (walking down from the worst, so a removal moves nothing still to come) and then the
     * prices it shows.
     */
    private void reconcileSide(PriceLadder book, BookSnapshot snapshot, boolean isBid, int depth, long venueNanos) {
        int shown = snapshot.count(isBid);
        // The worst shown price bounds what the snapshot could show; none when it shows the whole side.
        boolean bounded = shown >= depth && shown > 0;
        long worstShown = bounded ? snapshot.worst(isBid) : 0;

        for (int i = book.size() - 1; i >= 0; i--) {
            long price = book.priceAt(i);
            if (price == PRICE_NULL || snapshot.shows(isBid, price)) {
                continue;
            }
            boolean outOfView = bounded && (isBid ? price < worstShown : price > worstShown);
            if (outOfView) {
                dropIfNoLocalOrders(book, i);
            } else {
                reconcileLevel(book, price, 0, venueNanos);
            }
        }
        for (int i = 0; i < shown; i++) {
            reconcileLevel(book, snapshot.price(isBid, i), snapshot.size(isBid, i), venueNanos);
        }
    }

    /**
     * An out-of-view level holding our orders keeps its last known size and their queue position; a stale market
     * level is dropped rather than offered to aggressive orders.
     */
    private static void dropIfNoLocalOrders(PriceLadder book, int index) {
        OrderBookLevel level = book.levelAt(index);
        if (level.hasLocalOrders()) {
            level.outOfView = true;
        } else {
            book.removeAt(index);
        }
    }

    private void reconcileLevel(PriceLadder book, long price, long newSize, long venueNanos) {
        OrderBookLevel prevLevel = book.get(price);
        if (newSize == 0 && (prevLevel == null || !prevLevel.hasLocalOrders())) {
            if (prevLevel != null) {
                book.remove(price);
            }
            return;
        }
        long prevSize = prevLevel != null ? prevLevel.size : 0;
        if (prevLevel == null) {
            prevLevel = book.getOrCreate(price);
        }
        prevLevel.outOfView = false;
        boolean pending = pairing.enabled() && prevSize > newSize;
        if (pending) {
            prevLevel.recordShrink(prevSize - newSize, venueNanos, pairing);
        }
        // Trades were already taken off size in onTrade, so a shrink seen here is cancels until a trade claims it.
        applyCancels(prevLevel, prevSize, newSize, pending);
        prevLevel.size = newSize;
    }

    /**
     * Applies a level going from {@code prevSize} to {@code newSize} as cancels. When {@code pending}, records what the
     * cancels took from each order's queue ahead and from our consumed liquidity, so a trade claiming the shrink can
     * give it back.
     */
    private void applyCancels(OrderBookLevel level, long prevSize, long newSize, boolean pending) {
        if (pending) {
            for (LocalOrder localOrder : level.localOrders) {
                localOrder.phantomBeforeModify = localOrder.phantomVolume;
            }
        }
        queueModel.onModify(prevSize, newSize, level.localOrders);
        if (pending) {
            for (LocalOrder localOrder : level.localOrders) {
                localOrder.pendingCancelled += localOrder.phantomBeforeModify - localOrder.phantomVolume;
            }
        }
        if (level.consumed > 0) {
            long before = level.consumed;
            level.consumed = queueModel.consumedAfterCancels(before, Math.max(0, prevSize - newSize), newSize);
            if (pending) {
                level.pendingConsumedCleared += before - level.consumed;
            }
        }
    }

    /**
     * A trade claims {@code claimed} of its level's pending shrink: the queue is rebuilt as if the trade came before the
     * cancels. Orders that joined after the shrink began are set aside, since the trade came before them. The others
     * get back the queue the shrink took from them, the trade goes to the volume ahead of them first, and the shrink
     * the trade doesn't explain is applied again as cancels. The level's size stays as the book showed it.
     */
    private List<LocalOrderFill> claimShrink(OrderBookLevel level, long claimed, List<LocalOrderFill> allFills) {
        while (!level.localOrders.isEmpty() && level.localOrders.peekLast().joinedDuringShrink) {
            joinedAfterShrink.addFirst(level.localOrders.pollLast());
        }
        for (LocalOrder localOrder : level.localOrders) {
            localOrder.phantomVolume += localOrder.pendingCancelled;
            localOrder.pendingCancelled = 0;
        }
        level.consumed += level.pendingConsumedCleared;
        level.pendingConsumedCleared = 0;

        long unexplained = level.pendingShrink - claimed;
        long sizeAfterTrade = level.size + unexplained;
        List<LocalOrderFill> fills = queueModel.onTrade(claimed, level.localOrders);
        List<LocalOrderFill> into = append(allFills, fills);
        level.consumed = Math.min(level.consumed, sizeAfterTrade);
        level.pendingShrink = unexplained;
        applyCancels(level, sizeAfterTrade, level.size, true);

        while (!joinedAfterShrink.isEmpty()) {
            level.localOrders.addLast(joinedAfterShrink.pollFirst());
        }
        return into;
    }

    private void requireUnpaired() {
        if (pairing.enabled()) {
            throw new IllegalStateException(
                    "A book that pairs trades with book changes needs each record's venue time");
        }
    }

    private void clearFills(List<LocalOrderFill> fills) {
        for (LocalOrderFill fill : fills) {
            LocalOrder lo = fill.localOrder();
            if (lo.remaining == 0) {
                long clientOid = lo.order.getClientOidCounter();
                Side side = lo.order.decoder.side();
                boolean isBid = side == Side.Bid;
                if (isBid) {
                    localBidOrders.remove(clientOid);
                } else {
                    localAskOrders.remove(clientOid);
                }
                long price = lo.order.decoder.price();
                PriceLadder book = isBid ? bids : asks;
                OrderBookLevel level = book.get(price);
                if (level != null) {
                    level.localOrders.remove(lo);
                    if (level.size == 0 && !level.hasLocalOrders()) {
                        book.remove(price);
                    }
                }
            }
        }
    }
}
