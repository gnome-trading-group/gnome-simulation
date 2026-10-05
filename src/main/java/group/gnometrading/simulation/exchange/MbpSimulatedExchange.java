package group.gnometrading.simulation.exchange;

import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.Mbp10Decoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Mbp1Schema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.book.BidAskLevel;
import group.gnometrading.simulation.book.LocalOrder;
import group.gnometrading.simulation.book.LocalOrderFill;
import group.gnometrading.simulation.book.MatchPlan;
import group.gnometrading.simulation.book.MbpBook;
import group.gnometrading.simulation.book.OrderMatch;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.fee.FeeModel;
import group.gnometrading.simulation.latency.LatencyModel;
import group.gnometrading.simulation.queues.QueueModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class MbpSimulatedExchange implements SimulatedExchange {

    private static final long PRICE_NULL = Mbp10Decoder.priceNullValue();
    private static final long SIZE_NULL = Mbp10Decoder.sizeNullValue();
    private static final int MBP10_DEPTH = 10;
    private static final int MBP1_DEPTH = 1;

    private final FeeModel feeModel;
    private final LatencyModel networkLatency;
    private final LatencyModel orderProcessingLatency;
    private final MbpBook orderBook;
    private final SelfTradePrevention selfTradePrevention;
    private final AtomicLong orderCounter = new AtomicLong(0);
    private final PriorityQueue<Held> held =
            new PriorityQueue<>(Comparator.comparingLong(Held::dueNanos).thenComparingLong(Held::sequence));
    private long heldSequence;
    // Client OIDs of orders held inside their delay, which cannot be cancelled or amended yet.
    private final Set<Long> pendingOrders = new HashSet<>();

    public MbpSimulatedExchange(
            FeeModel feeModel,
            LatencyModel networkLatency,
            LatencyModel orderProcessingLatency,
            QueueModel queueModel) {
        this(feeModel, networkLatency, orderProcessingLatency, queueModel, SelfTradePrevention.CANCEL_INCOMING);
    }

    public MbpSimulatedExchange(
            FeeModel feeModel,
            LatencyModel networkLatency,
            LatencyModel orderProcessingLatency,
            QueueModel queueModel,
            SelfTradePrevention selfTradePrevention) {
        this.feeModel = feeModel;
        this.networkLatency = networkLatency;
        this.orderProcessingLatency = orderProcessingLatency;
        this.orderBook = new MbpBook(queueModel);
        this.selfTradePrevention = selfTradePrevention;
    }

    @Override
    public List<OrderExecutionReport> onMarketData(Schema data) {
        if (data instanceof Mbp10Schema mbp10) {
            return onMbp10(mbp10);
        } else if (data instanceof Mbp1Schema mbp1) {
            return onMbp1(mbp1);
        }
        throw new IllegalArgumentException("Unsupported schema type: " + data.schemaType);
    }

    @Override
    public List<OrderExecutionReport> submitOrder(Order incoming, long nowNanos) {
        // The caller may reuse its message object; the exchange keeps its own copy for as long as the order lives.
        Order order = copy(incoming);
        long clientOid = order.getClientOidCounter();
        // Auto-generate clientOid if not set (counter == 0 indicates unset)
        if (clientOid == 0) {
            clientOid = orderCounter.incrementAndGet();
            order.encodeClientOid(clientOid, order.getClientOidStrategyId());
        }

        OrderExecutionReport reject = rejectOnArrival(order);
        if (reject != null) {
            return List.of(reject);
        }

        // Whether an order takes is decided on arrival, as venue speed bumps do; it matches when its delay is up.
        // A post-only order never takes: if it would cross it is rejected, without waiting out a taker delay.
        boolean takes = !order.decoder.flags().postOnly()
                && (order.decoder.orderType() == OrderType.MARKET
                        || !orderBook
                                .planMatches(order, selfTradePrevention)
                                .matches()
                                .isEmpty());
        pendingOrders.add(clientOid);
        hold(nowNanos + orderProcessingLatency.simulate(!takes), order);
        return List.of();
    }

    /** The reject for an order the exchange refuses as soon as it arrives, or null if it is accepted. */
    private OrderExecutionReport rejectOnArrival(Order order) {
        Side side = order.decoder.side();
        if (order.decoder.size() <= 0 || side == null || side == Side.None) {
            return rejected(order);
        }
        OrderType orderType = order.decoder.orderType();
        if (orderType == OrderType.LIMIT && order.decoder.price() <= 0) {
            return rejected(order);
        }
        if (orderType == OrderType.MARKET && order.decoder.flags().postOnly()) {
            return rejectedPostOnly(order);
        }
        if (orderType != OrderType.MARKET && orderType != OrderType.LIMIT) {
            throw new IllegalArgumentException("Unexpected order type: " + orderType);
        }
        return null;
    }

    @Override
    public List<OrderExecutionReport> cancelOrder(CancelOrder cancel, long nowNanos) {
        hold(nowNanos + orderProcessingLatency.simulate(true), copy(cancel));
        return List.of();
    }

    @Override
    public List<OrderExecutionReport> modifyOrder(ModifyOrder incoming, long nowNanos) {
        ModifyOrder modify = copy(incoming);
        // An amend to a price that crosses takes liquidity, so it waits the taker delay like a new taking order.
        LocalOrder working = orderBook.findLocalOrder(modify.getClientOidCounter());
        boolean takes = working != null
                && working.order.decoder.price() != modify.decoder.price()
                && !orderBook
                        .planMatches(
                                working.order.decoder.side(),
                                OrderType.LIMIT,
                                modify.decoder.price(),
                                modify.decoder.size(),
                                selfTradePrevention)
                        .matches()
                        .isEmpty();
        hold(nowNanos + orderProcessingLatency.simulate(!takes), modify);
        return List.of();
    }

    @Override
    public List<OrderExecutionReport> processDue(long nowNanos) {
        List<OrderExecutionReport> reports = null;
        while (!held.isEmpty() && held.peek().dueNanos() <= nowNanos) {
            List<OrderExecutionReport> processed = process(held.poll().message());
            if (reports == null) {
                reports = new ArrayList<>(processed);
            } else {
                reports.addAll(processed);
            }
        }
        return reports == null ? List.of() : reports;
    }

    @Override
    public long nextDueNanos() {
        return held.isEmpty() ? Long.MAX_VALUE : held.peek().dueNanos();
    }

    private void hold(long dueNanos, Object message) {
        held.add(new Held(dueNanos, heldSequence++, message));
    }

    /**
     * An order still inside its delay is pending and cannot be cancelled or amended, as on Polymarket; the cancel or
     * modify is refused and the order still matches when its delay ends.
     */
    private List<OrderExecutionReport> process(Object message) {
        if (message instanceof Order order) {
            pendingOrders.remove(order.getClientOidCounter());
            return order.decoder.orderType() == OrderType.MARKET ? handleMarketOrder(order) : handleLimitOrder(order);
        }
        if (message instanceof CancelOrder cancel) {
            if (pendingOrders.contains(cancel.getClientOidCounter())) {
                return List.of(cancelRejected(cancel));
            }
            return executeCancel(cancel);
        }
        ModifyOrder modify = (ModifyOrder) message;
        if (pendingOrders.contains(modify.getClientOidCounter())) {
            return List.of(cancelRejected(modify));
        }
        return executeModify(modify);
    }

    private List<OrderExecutionReport> executeCancel(CancelOrder cancel) {
        long clientOid = cancel.getClientOidCounter();
        if (orderBook.cancelOrder(clientOid)) {
            return List.of(canceled(cancel));
        }
        return List.of(cancelRejected(cancel));
    }

    private static Order copy(Order order) {
        Order copy = new Order();
        copy.buffer.putBytes(0, order.buffer, 0, order.totalMessageSize());
        copy.wrap(copy.buffer);
        return copy;
    }

    private static CancelOrder copy(CancelOrder cancel) {
        CancelOrder copy = new CancelOrder();
        copy.buffer.putBytes(0, cancel.buffer, 0, cancel.totalMessageSize());
        copy.wrap(copy.buffer);
        return copy;
    }

    private static ModifyOrder copy(ModifyOrder modify) {
        ModifyOrder copy = new ModifyOrder();
        copy.buffer.putBytes(0, modify.buffer, 0, modify.totalMessageSize());
        copy.wrap(copy.buffer);
        return copy;
    }

    /** A message held until it is due; {@code sequence} keeps arrival order among messages due at once. */
    private record Held(long dueNanos, long sequence, Object message) {}

    private List<OrderExecutionReport> executeModify(ModifyOrder modify) {
        long clientOid = modify.getClientOidCounter();
        long newPrice = modify.decoder.price();
        long newOrderQty = modify.decoder.size();
        LocalOrder working = orderBook.findLocalOrder(clientOid);
        List<OrderExecutionReport> selfTradeCancels = List.of();
        long newRemaining = working == null ? 0 : newOrderQty - (working.order.decoder.size() - working.remaining);
        if (working != null && working.order.decoder.price() != newPrice && newRemaining > 0) {
            MatchPlan plan = orderBook.planMatches(
                    working.order.decoder.side(), OrderType.LIMIT, newPrice, newRemaining, selfTradePrevention);
            if (!plan.matches().isEmpty()) {
                return modifyAcrossTheSpread(modify, working, plan, newPrice, newOrderQty);
            }
            if (plan.stoppedAtSelf()) {
                // The new price would trade only with our own resting order: the amended order is cancelled.
                long filled = working.order.decoder.size() - working.remaining;
                orderBook.cancelOrder(clientOid);
                return List.of(report(working.order, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, filled, 0, 0));
            }
            selfTradeCancels = cancelResting(plan);
        }
        if (orderBook.modifyLocalOrder(clientOid, newPrice, newOrderQty)) {
            LocalOrder replaced = orderBook.findLocalOrder(clientOid);
            OrderExecutionReport report = makeReport(
                    modify.getClientOidCounter(),
                    modify.getClientOidStrategyId(),
                    ExecType.NEW,
                    OrderStatus.NEW,
                    0,
                    0,
                    newOrderQty - replaced.remaining,
                    replaced.remaining,
                    0);
            report.encoder.exchangeId((short) modify.decoder.exchangeId()).securityId(modify.decoder.securityId());
            if (selfTradeCancels.isEmpty()) {
                return List.of(report);
            }
            List<OrderExecutionReport> reports = new ArrayList<>(selfTradeCancels);
            reports.add(report);
            return reports;
        }
        // FIX protocol sends CANCEL_REJECT when rejecting a modify/replace order
        return List.of(cancelRejected(modify));
    }

    /** An amend to a price that crosses the book takes liquidity like a new order at that price. */
    private List<OrderExecutionReport> modifyAcrossTheSpread(
            ModifyOrder modify, LocalOrder working, MatchPlan plan, long newPrice, long newOrderQty) {
        Order order = working.order;
        long filledBefore = order.decoder.size() - working.remaining;
        long newRemaining = newOrderQty - filledBefore;
        if (order.decoder.flags().postOnly()) {
            return List.of(cancelRejected(modify));
        }

        orderBook.cancelOrder(order.getClientOidCounter());
        order.encoder.price(newPrice).size(newOrderQty);
        orderBook.consume(order.decoder.side(), plan.matches());
        List<OrderExecutionReport> reports = cancelResting(plan);

        Totals totals = Totals.of(plan.matches(), feeModel);
        long remaining = newRemaining - totals.filled();
        OrderExecutionReport ack = report(order, ExecType.NEW, OrderStatus.NEW, 0, 0, filledBefore, newRemaining, 0);
        OrderExecutionReport fill = report(
                order,
                remaining == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL,
                remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                totals.filled(),
                totals.vwap(),
                filledBefore + totals.filled(),
                remaining,
                totals.fee());
        fill.encoder.liquidity(Liquidity.TAKER);
        reports.add(ack);
        reports.add(fill);
        if (remaining > 0) {
            if (plan.stoppedAtSelf()) {
                reports.add(report(
                        order, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, filledBefore + totals.filled(), 0, 0));
            } else {
                orderBook.addLocalOrder(order, remaining);
            }
        }
        return reports;
    }

    @Override
    public long simulateNetworkLatency() {
        return networkLatency.simulate();
    }

    @Override
    public List<SchemaType> getSupportedSchemas() {
        return List.of(SchemaType.MBP_10, SchemaType.MBP_1);
    }

    private List<OrderExecutionReport> onMbp10(Mbp10Schema schema) {
        Action action = schema.decoder.action();
        if (action == Action.Add || action == Action.Cancel || action == Action.Modify) {
            List<BidAskLevel> levels = extractMbp10Levels(schema);
            List<LocalOrderFill> fills = orderBook.onMarketUpdate(levels, MBP10_DEPTH);
            return mapFillsToReports(fills);
        } else if (action == Action.Trade) {
            long price = schema.decoder.price();
            long size = schema.decoder.size();
            var side = schema.decoder.side();
            List<LocalOrderFill> fills = orderBook.onTrade(price, size, side);
            return mapFillsToReports(fills);
        }
        return List.of();
    }

    private List<OrderExecutionReport> onMbp1(Mbp1Schema schema) {
        Action action = schema.decoder.action();
        if (action == Action.Add || action == Action.Cancel || action == Action.Modify) {
            List<BidAskLevel> levels = extractMbp1Levels(schema);
            List<LocalOrderFill> fills = orderBook.onMarketUpdate(levels, MBP1_DEPTH);
            return mapFillsToReports(fills);
        } else if (action == Action.Trade) {
            long price = schema.decoder.price();
            long size = schema.decoder.size();
            var side = schema.decoder.side();
            List<LocalOrderFill> fills = orderBook.onTrade(price, size, side);
            return mapFillsToReports(fills);
        }
        return List.of();
    }

    private List<OrderExecutionReport> mapFillsToReports(List<LocalOrderFill> fills) {
        List<OrderExecutionReport> reports = new ArrayList<>(fills.size());
        for (LocalOrderFill fill : fills) {
            reports.add(mapFillToReport(fill.localOrder(), fill.fillSize(), fill.remainingAfterFill()));
        }
        return reports;
    }

    private OrderExecutionReport mapFillToReport(LocalOrder localOrder, long filledQty, long remainingAfterFill) {
        long price = localOrder.order.decoder.price();
        long cumulativeQty = localOrder.order.decoder.size() - remainingAfterFill;

        ExecType execType = remainingAfterFill == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL;
        OrderStatus orderStatus = remainingAfterFill == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;

        OrderExecutionReport report = makeReport(
                localOrder.order.getClientOidCounter(),
                localOrder.order.getClientOidStrategyId(),
                execType,
                orderStatus,
                filledQty,
                price,
                cumulativeQty,
                remainingAfterFill,
                (long) feeModel.calculateFee(price, filledQty, true));
        report.encoder
                .exchangeId((short) localOrder.order.decoder.exchangeId())
                .securityId(localOrder.order.decoder.securityId())
                .liquidity(Liquidity.MAKER);
        return report;
    }

    private List<OrderExecutionReport> handleMarketOrder(Order order) {
        MatchPlan plan = orderBook.planMatches(order, selfTradePrevention);
        if (plan.matches().isEmpty()) {
            List<OrderExecutionReport> reports = cancelResting(plan);
            reports.add(
                    plan.stoppedAtSelf()
                            ? report(order, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, 0, 0, 0)
                            : rejected(order));
            return reports;
        }

        orderBook.consume(order.decoder.side(), plan.matches());
        List<OrderExecutionReport> reports = cancelResting(plan);
        Totals totals = Totals.of(plan.matches(), feeModel);
        long remaining = order.decoder.size() - totals.filled();
        OrderExecutionReport fill = report(
                order,
                remaining == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL,
                remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                totals.filled(),
                totals.vwap(),
                totals.filled(),
                remaining,
                totals.fee());
        fill.encoder.liquidity(Liquidity.TAKER);
        reports.add(fill);
        if (remaining > 0) {
            reports.add(report(order, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, totals.filled(), 0, 0));
        }
        return reports;
    }

    private List<OrderExecutionReport> handleLimitOrder(Order order) {
        boolean postOnly = order.decoder.flags().postOnly();
        MatchPlan plan = orderBook.planMatches(order, selfTradePrevention);
        if (postOnly && !plan.matches().isEmpty()) {
            return List.of(rejectedPostOnly(order));
        }
        if (plan.matches().isEmpty()) {
            return restLimitOrder(order, plan);
        }
        return crossLimitOrder(order, plan, plan.stoppedAtSelf());
    }

    /**
     * A limit order that takes no market liquidity. It may still reach our own resting order, so the self-trade rule
     * applies before it rests; otherwise our own orders would sit crossed.
     */
    private List<OrderExecutionReport> restLimitOrder(Order order, MatchPlan plan) {
        // A fill-or-kill that cannot fill does nothing at all, including to our resting orders.
        if (order.decoder.timeInForce() == TimeInForce.FILL_OR_KILL) {
            return List.of(rejected(order));
        }
        List<OrderExecutionReport> reports = cancelResting(plan);
        if (plan.stoppedAtSelf()) {
            reports.add(report(order, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, 0, 0, 0));
            return reports;
        }
        if (order.decoder.timeInForce() == TimeInForce.IMMEDIATE_OR_CANCELED) {
            reports.add(rejected(order));
            return reports;
        }
        orderBook.addLocalOrder(order);
        reports.add(report(order, ExecType.NEW, OrderStatus.NEW, 0, 0, 0, order.decoder.size(), 0));
        return reports;
    }

    /** An aggressive limit order that crosses the spread takes liquidity. */
    private List<OrderExecutionReport> crossLimitOrder(Order order, MatchPlan plan, boolean stoppedAtSelf) {
        long orderSize = order.decoder.size();
        TimeInForce tif = order.decoder.timeInForce();
        Totals totals = Totals.of(plan.matches(), feeModel);
        long remaining = orderSize - totals.filled();
        if (remaining > 0 && tif == TimeInForce.FILL_OR_KILL) {
            return List.of(rejected(order));
        }

        orderBook.consume(order.decoder.side(), plan.matches());
        List<OrderExecutionReport> reports = cancelResting(plan);
        OrderExecutionReport fill = report(
                order,
                remaining == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL,
                remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                totals.filled(),
                totals.vwap(),
                totals.filled(),
                remaining,
                totals.fee());
        fill.encoder.liquidity(Liquidity.TAKER);
        if (remaining == 0) {
            reports.add(fill);
        } else if (tif == TimeInForce.IMMEDIATE_OR_CANCELED || stoppedAtSelf) {
            reports.add(fill);
            reports.add(report(order, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, totals.filled(), 0, 0));
        } else {
            orderBook.addLocalOrder(order, remaining);
            reports.add(report(order, ExecType.NEW, OrderStatus.NEW, 0, 0, 0, orderSize, 0));
            reports.add(fill);
        }
        return reports;
    }

    /** Cancels the resting orders the self-trade rule removed, returning a CANCELED report for each. */
    private List<OrderExecutionReport> cancelResting(MatchPlan plan) {
        List<OrderExecutionReport> reports = new ArrayList<>();
        for (LocalOrder resting : plan.restingToCancel()) {
            long filled = resting.order.decoder.size() - resting.remaining;
            orderBook.cancelOrder(resting.order.getClientOidCounter());
            reports.add(report(resting.order, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, filled, 0, 0));
        }
        return reports;
    }

    private record Totals(long filled, double notional, double feeTotal) {
        static Totals of(List<OrderMatch> matches, FeeModel feeModel) {
            long filled = 0;
            double notional = 0;
            double fee = 0;
            for (OrderMatch match : matches) {
                filled += match.size();
                notional += (double) match.price() * match.size();
                fee += feeModel.calculateFee(match.price(), match.size(), false);
            }
            return new Totals(filled, notional, fee);
        }

        long vwap() {
            return (long) (notional / filled);
        }

        long fee() {
            return (long) feeTotal;
        }
    }

    /** A report for {@code order}, stamped with its client OID and listing. */
    private OrderExecutionReport report(
            Order order,
            ExecType execType,
            OrderStatus orderStatus,
            long filledQty,
            long fillPrice,
            long cumulativeQty,
            long leavesQty,
            long feeScaled) {
        OrderExecutionReport report = makeReport(
                order.getClientOidCounter(),
                order.getClientOidStrategyId(),
                execType,
                orderStatus,
                filledQty,
                fillPrice,
                cumulativeQty,
                leavesQty,
                feeScaled);
        report.encoder.exchangeId((short) order.decoder.exchangeId()).securityId(order.decoder.securityId());
        return report;
    }

    // --- Factory helpers ---

    private OrderExecutionReport makeReport(
            long clientOid,
            int strategyId,
            ExecType execType,
            OrderStatus orderStatus,
            long filledQty,
            long fillPrice,
            long cumulativeQty,
            long leavesQty,
            long feeScaled) {
        OrderExecutionReport report = new OrderExecutionReport();
        report.encodeClientOid(clientOid, strategyId);
        report.encoder
                .execType(execType)
                .orderStatus(orderStatus)
                .filledQty(filledQty)
                .fillPrice(fillPrice)
                .cumulativeQty(cumulativeQty)
                .leavesQty(leavesQty)
                .fee(feeScaled);
        return report;
    }

    private OrderExecutionReport rejected(Order order) {
        OrderExecutionReport report = makeReport(
                order.getClientOidCounter(),
                order.getClientOidStrategyId(),
                ExecType.REJECT,
                OrderStatus.REJECTED,
                0,
                0,
                0,
                0,
                0);
        report.encoder.exchangeId((short) order.decoder.exchangeId()).securityId(order.decoder.securityId());
        return report;
    }

    private OrderExecutionReport rejectedPostOnly(Order order) {
        OrderExecutionReport report = makeReport(
                order.getClientOidCounter(),
                order.getClientOidStrategyId(),
                ExecType.REJECT,
                OrderStatus.REJECTED,
                0,
                0,
                0,
                0,
                0);
        report.encoder
                .exchangeId((short) order.decoder.exchangeId())
                .securityId(order.decoder.securityId())
                .rejectReason(RejectReason.POST_ONLY_WOULD_CROSS);
        return report;
    }

    private OrderExecutionReport cancelRejected(CancelOrder cancel) {
        OrderExecutionReport report = makeReport(
                cancel.getClientOidCounter(),
                cancel.getClientOidStrategyId(),
                ExecType.CANCEL_REJECT,
                OrderStatus.NEW,
                0,
                0,
                0,
                0,
                0);
        report.encoder.exchangeId((short) cancel.decoder.exchangeId()).securityId(cancel.decoder.securityId());
        return report;
    }

    private OrderExecutionReport cancelRejected(ModifyOrder modify) {
        OrderExecutionReport report = makeReport(
                modify.getClientOidCounter(),
                modify.getClientOidStrategyId(),
                ExecType.CANCEL_REJECT,
                OrderStatus.NEW,
                0,
                0,
                0,
                0,
                0);
        report.encoder.exchangeId((short) modify.decoder.exchangeId()).securityId(modify.decoder.securityId());
        return report;
    }

    private OrderExecutionReport canceled(CancelOrder cancel) {
        OrderExecutionReport report = makeReport(
                cancel.getClientOidCounter(),
                cancel.getClientOidStrategyId(),
                ExecType.CANCEL,
                OrderStatus.CANCELED,
                0,
                0,
                0,
                0,
                0);
        report.encoder.exchangeId((short) cancel.decoder.exchangeId()).securityId(cancel.decoder.securityId());
        return report;
    }

    private List<BidAskLevel> extractMbp10Levels(Mbp10Schema schema) {
        var decoder = schema.decoder;
        List<BidAskLevel> levels = new ArrayList<>(10);
        addLevel(levels, decoder.bidPrice0(), decoder.bidSize0(), decoder.askPrice0(), decoder.askSize0());
        addLevel(levels, decoder.bidPrice1(), decoder.bidSize1(), decoder.askPrice1(), decoder.askSize1());
        addLevel(levels, decoder.bidPrice2(), decoder.bidSize2(), decoder.askPrice2(), decoder.askSize2());
        addLevel(levels, decoder.bidPrice3(), decoder.bidSize3(), decoder.askPrice3(), decoder.askSize3());
        addLevel(levels, decoder.bidPrice4(), decoder.bidSize4(), decoder.askPrice4(), decoder.askSize4());
        addLevel(levels, decoder.bidPrice5(), decoder.bidSize5(), decoder.askPrice5(), decoder.askSize5());
        addLevel(levels, decoder.bidPrice6(), decoder.bidSize6(), decoder.askPrice6(), decoder.askSize6());
        addLevel(levels, decoder.bidPrice7(), decoder.bidSize7(), decoder.askPrice7(), decoder.askSize7());
        addLevel(levels, decoder.bidPrice8(), decoder.bidSize8(), decoder.askPrice8(), decoder.askSize8());
        addLevel(levels, decoder.bidPrice9(), decoder.bidSize9(), decoder.askPrice9(), decoder.askSize9());
        return levels;
    }

    private List<BidAskLevel> extractMbp1Levels(Mbp1Schema schema) {
        var decoder = schema.decoder;
        List<BidAskLevel> levels = new ArrayList<>(1);
        addLevel(levels, decoder.bidPrice0(), decoder.bidSize0(), decoder.askPrice0(), decoder.askSize0());
        return levels;
    }

    private void addLevel(List<BidAskLevel> levels, long bidPx, long bidSz, long askPx, long askSz) {
        if (bidPx != PRICE_NULL || askPx != PRICE_NULL) {
            levels.add(new BidAskLevel(
                    bidPx == PRICE_NULL ? PRICE_NULL : bidPx,
                    bidSz == SIZE_NULL ? 0 : bidSz,
                    askPx == PRICE_NULL ? PRICE_NULL : askPx,
                    askSz == SIZE_NULL ? 0 : askSz));
        }
    }
}
