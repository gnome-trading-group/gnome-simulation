package group.gnometrading.simulation.book;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.simulation.queues.QueueModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MbpBook {

    private static final long PRICE_NULL = Long.MIN_VALUE;

    private final QueueModel queueModel;

    // TreeMap bids: descending order (best bid first), asks: ascending (best ask first)
    private final TreeMap<Long, OrderBookLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, OrderBookLevel> asks = new TreeMap<>();

    // local_orders[side][clientOidCounter] -> LocalOrder
    private final Map<Long, LocalOrder> localBidOrders = new HashMap<>();
    private final Map<Long, LocalOrder> localAskOrders = new HashMap<>();

    public MbpBook(QueueModel queueModel) {
        this.queueModel = queueModel;
    }

    public Long getBestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    public Long getBestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /**
     * Reconciles the book against the provided MBP levels, adjusting phantom volumes via the queue model.
     * Returns fills if any local orders were crossed after the update.
     */
    /**
     * Reconciles the book against a snapshot that shows up to {@code depth} levels per side. A side showing fewer
     * than {@code depth} levels is the whole side; otherwise prices beyond its worst shown level are out of view, and
     * our orders resting there keep their queue position until the level is shown again.
     */
    public List<LocalOrderFill> onMarketUpdate(List<BidAskLevel> levels, int depth) {
        Map<Long, Long> currBids = new HashMap<>();
        Map<Long, Long> currAsks = new HashMap<>();
        for (BidAskLevel level : levels) {
            if (level.bidPrice() != PRICE_NULL) {
                currBids.put(level.bidPrice(), level.bidSize());
            }
            if (level.askPrice() != PRICE_NULL) {
                currAsks.put(level.askPrice(), level.askSize());
            }
        }

        reconcileSide(bids, currBids, true, depth);
        reconcileSide(asks, currAsks, false, depth);

        if (localBidOrders.isEmpty() && localAskOrders.isEmpty()) {
            return Collections.emptyList();
        }

        Long bestBid = getBestBid();
        Long bestAsk = getBestAsk();
        if (bestBid == null || bestAsk == null || bestBid < bestAsk) {
            return Collections.emptyList();
        }

        // Book is crossed — check for fills
        List<LocalOrderFill> allFills = new ArrayList<>();
        checkBidFills(bestAsk, allFills);
        checkAskFills(bestBid, allFills);

        clearFills(allFills);
        return allFills;
    }

    private void checkBidFills(Long bestAsk, List<LocalOrderFill> allFills) {
        for (long bidPrice : bids.keySet()) {
            if (bidPrice < bestAsk) {
                break;
            }
            OrderBookLevel bidLevel = bids.get(bidPrice);
            if (bidLevel == null || !bidLevel.hasLocalOrders()) {
                continue;
            }
            bypassPhantom(bidLevel.localOrders);
            long remainingToFill =
                    bidLevel.localOrders.stream().mapToLong(lo -> lo.remaining).sum();
            for (long askPrice : asks.keySet()) {
                if (askPrice > bidPrice || remainingToFill == 0) {
                    break;
                }
                OrderBookLevel askLevel = asks.get(askPrice);
                if (askLevel == null || askLevel.available() == 0) {
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

    private void checkAskFills(Long bestBid, List<LocalOrderFill> allFills) {
        for (long askPrice : asks.keySet()) {
            if (askPrice > bestBid) {
                break;
            }
            OrderBookLevel askLevel = asks.get(askPrice);
            if (askLevel == null || !askLevel.hasLocalOrders()) {
                continue;
            }
            bypassPhantom(askLevel.localOrders);
            long remainingToFill =
                    askLevel.localOrders.stream().mapToLong(lo -> lo.remaining).sum();
            for (long bidPrice : bids.keySet()) {
                if (bidPrice < askPrice || remainingToFill == 0) {
                    break;
                }
                OrderBookLevel bidLevel = bids.get(bidPrice);
                if (bidLevel == null || bidLevel.available() == 0) {
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

    /**
     * Processes a trade from the market feed, filling local orders on the opposite side.
     */
    public List<LocalOrderFill> onTrade(long price, long size, Side tradeSide) {
        // Runs even with no local orders: the trade takes market volume off each level it reaches, which is what lets a
        // later snapshot's shrink be read as cancels.
        // The trade side is the aggressor side; our local orders are on the opposite side
        boolean tradeIsBid = tradeSide == Side.Bid;
        TreeMap<Long, OrderBookLevel> oppBook = tradeIsBid ? asks : bids;
        List<LocalOrderFill> allFills = new ArrayList<>();

        long remainingSize = size;
        for (Map.Entry<Long, OrderBookLevel> entry : oppBook.entrySet()) {
            if (remainingSize <= 0) {
                break;
            }
            long levelPrice = entry.getKey();
            // For ask levels: stop if ask price > trade price (trade can't reach here)
            // For bid levels: stop if bid price < trade price
            if (tradeIsBid ? levelPrice > price : levelPrice < price) {
                break;
            }

            OrderBookLevel level = entry.getValue();
            if (level == null) {
                throw new IllegalStateException("Malformed local book: null level at price " + levelPrice);
            }

            List<LocalOrderFill> fills = queueModel.onTrade(remainingSize, level.localOrders);
            allFills.addAll(fills);
            long filledQty = fills.stream().mapToLong(LocalOrderFill::fillSize).sum();
            remainingSize -= filledQty;

            // Consume remaining market (non-local) volume at this level
            long leftToConsume = Math.min(remainingSize, level.size);
            remainingSize -= leftToConsume;
            level.size -= leftToConsume;
            // What we took can't outlast the level: once the market has swept it, nothing of it is left to block.
            level.consumed = Math.min(level.consumed, level.size);
        }

        clearFills(allFills);
        return allFills;
    }

    /**
     * Places a local order into the book with phantom volume equal to the current displayed depth at the price level.
     */
    public void addLocalOrder(Order order, long remaining) {
        Side side = order.decoder.side();
        long price = order.decoder.price();
        long clientOid = order.getClientOidCounter();
        TreeMap<Long, OrderBookLevel> book = side == Side.Bid ? bids : asks;
        Map<Long, LocalOrder> localOrders = side == Side.Bid ? localBidOrders : localAskOrders;

        if (localOrders.containsKey(clientOid)) {
            throw new IllegalArgumentException("Duplicate client OID: " + clientOid);
        }

        OrderBookLevel level = book.get(price);
        if (level == null) {
            level = new OrderBookLevel(price, 0);
            book.put(price, level);
        }

        LocalOrder localOrder = new LocalOrder(order, remaining, level.queueOnJoin());
        localOrders.put(clientOid, localOrder);
        level.localOrders.addLast(localOrder);
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

        TreeMap<Long, OrderBookLevel> book = side == Side.Bid ? bids : asks;
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
        TreeMap<Long, OrderBookLevel> book = localBidOrders.containsKey(clientOid) ? bids : asks;
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
            TreeMap<Long, OrderBookLevel> book,
            LocalOrder localOrder,
            long newPrice,
            long newOrderQty,
            long newRemaining) {
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

        OrderBookLevel newLevel = book.computeIfAbsent(newPrice, price -> new OrderBookLevel(price, 0));
        localOrder.phantomVolume = newLevel.queueOnJoin();
        newLevel.localOrders.addLast(localOrder);
    }

    /** A size decrease keeps its place in the queue; an increase loses priority and rejoins at the back. */
    private static void resize(
            TreeMap<Long, OrderBookLevel> book, LocalOrder localOrder, long newOrderQty, long newRemaining) {
        boolean increase = newRemaining > localOrder.remaining;
        localOrder.order.encoder.size(newOrderQty);
        localOrder.remaining = newRemaining;
        if (increase) {
            OrderBookLevel level = book.get(localOrder.order.decoder.price());
            level.localOrders.remove(localOrder);
            localOrder.phantomVolume = level.queueOnJoin();
            level.localOrders.addLast(localOrder);
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
        TreeMap<Long, OrderBookLevel> oppBook = isBuy ? asks : bids;

        for (Map.Entry<Long, OrderBookLevel> entry : oppBook.entrySet()) {
            if (remaining == 0) {
                break;
            }
            long levelPrice = entry.getKey();
            if (orderType == OrderType.LIMIT && (isBuy ? levelPrice > orderPrice : levelPrice < orderPrice)) {
                break;
            }
            OrderBookLevel level = entry.getValue();
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
        TreeMap<Long, OrderBookLevel> oppBook = takerSide == Side.Bid ? asks : bids;
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

    TreeMap<Long, OrderBookLevel> bids() {
        return bids;
    }

    TreeMap<Long, OrderBookLevel> asks() {
        return asks;
    }

    Map<Long, LocalOrder> localBidOrders() {
        return localBidOrders;
    }

    Map<Long, LocalOrder> localAskOrders() {
        return localAskOrders;
    }

    private void reconcileSide(TreeMap<Long, OrderBookLevel> book, Map<Long, Long> curr, boolean isBid, int depth) {
        // The worst shown price bounds what the snapshot could show; null when it shows the whole side.
        Long worstShown = curr.size() < depth ? null : worstPrice(curr, isBid);
        Set<Long> allPrices = new HashSet<>(book.keySet());
        allPrices.addAll(curr.keySet());

        for (long price : allPrices) {
            if (price == PRICE_NULL) {
                continue;
            }
            boolean outOfView = worstShown != null && (isBid ? price < worstShown : price > worstShown);
            if (outOfView) {
                dropIfNoLocalOrders(book, price);
            } else {
                reconcileLevel(book, price, curr.getOrDefault(price, 0L));
            }
        }
    }

    private static long worstPrice(Map<Long, Long> curr, boolean isBid) {
        return isBid ? Collections.min(curr.keySet()) : Collections.max(curr.keySet());
    }

    /**
     * An out-of-view level holding our orders keeps its last known size and their queue position; a stale market
     * level is dropped rather than offered to aggressive orders.
     */
    private static void dropIfNoLocalOrders(TreeMap<Long, OrderBookLevel> book, long price) {
        OrderBookLevel level = book.get(price);
        if (level == null) {
            return;
        }
        if (level.hasLocalOrders()) {
            level.outOfView = true;
        } else {
            book.remove(price);
        }
    }

    private void reconcileLevel(TreeMap<Long, OrderBookLevel> book, long price, long newSize) {
        OrderBookLevel prevLevel = book.get(price);
        if (newSize == 0 && (prevLevel == null || !prevLevel.hasLocalOrders())) {
            book.remove(price);
            return;
        }
        long prevSize = prevLevel != null ? prevLevel.size : 0;
        if (prevLevel == null) {
            prevLevel = new OrderBookLevel(price, 0);
            book.put(price, prevLevel);
        }
        prevLevel.outOfView = false;
        queueModel.onModify(prevSize, newSize, prevLevel.localOrders);
        if (prevLevel.consumed > 0) {
            // Trades were already taken off size in onTrade, so a shrink seen here is cancels.
            prevLevel.consumed =
                    queueModel.consumedAfterCancels(prevLevel.consumed, Math.max(0, prevSize - newSize), newSize);
        }
        prevLevel.size = newSize;
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
                TreeMap<Long, OrderBookLevel> book = isBid ? bids : asks;
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
