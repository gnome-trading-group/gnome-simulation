package group.gnometrading.simulation.exchange;

import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeCancel;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeModify;
import static group.gnometrading.simulation.exchange.MBPSubmitTest.makeOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.Mbp10Decoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Mbp1Schema;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.book.LocalOrder;
import group.gnometrading.simulation.book.OrderBookLevel;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.latency.GaussianLatency;
import group.gnometrading.simulation.latency.LatencyModel;
import group.gnometrading.simulation.latency.MakerTakerLatencyModel;
import group.gnometrading.simulation.latency.StaticLatency;
import group.gnometrading.simulation.queues.OptimisticQueueModel;
import group.gnometrading.simulation.queues.ProbabilisticQueueModel;
import group.gnometrading.simulation.queues.QueueModel;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Drives the exchange with random sequences of snapshots, trades, orders, cancels and modifies, across queue models,
 * self-trade rules, feed depths and delays, and checks rules that must hold whatever happens: report sequences are
 * consistent with each order's quantities, fills respect limit prices, nothing happens to an order after it ends, the
 * book's own structures stay consistent, our resting orders never cross each other, every order can be ended, and
 * the same seed gives the same reports.
 */
class MbpExchangeInvariantTest {

    private static final int TRIALS = Integer.getInteger("invariant.trials", 400);
    private static final int STEPS = 400;
    private static final long PRICE_NULL = Mbp10Decoder.priceNullValue();
    private static final long SIZE_NULL = Mbp10Decoder.sizeNullValue();

    @Test
    void invariantsHoldOverRandomSequences() throws Exception {
        for (int seed = 0; seed < TRIALS; seed++) {
            new Trial(seed).run();
        }
    }

    @Test
    void sameSeedGivesSameReports() throws Exception {
        for (int seed = 0; seed < 50; seed++) {
            assertEquals(new Trial(seed).run(), new Trial(seed).run(), "seed " + seed);
        }
    }

    /**
     * The replayed feed never shows our trades. On a one-level book that only repeats or grows, whatever mix of
     * takers we send, we can never take more than the level ever showed: its first size plus each increase.
     */
    @Test
    void takersNeverTakeMoreThanTheBookShowed() {
        for (int seed = 0; seed < TRIALS; seed++) {
            Random random = new Random(seed);
            MbpSimulatedExchange exchange = new MbpSimulatedExchange(
                    (price, quantity, isMaker) -> 0,
                    new StaticLatency(0),
                    new StaticLatency(0),
                    random.nextBoolean() ? new OptimisticQueueModel() : new RiskAverseQueueModel());
            long shown = 10 + random.nextInt(30);
            long budget = shown;
            long taken = 0;
            exchange.onMarketData(Trial.mbp10(List.<long[]>of(new long[] {90, 50, 100, shown})));
            for (int step = 0; step < 200; step++) {
                int action = random.nextInt(4);
                if (action == 0) {
                    long grown = shown + random.nextInt(5);
                    budget += grown - shown;
                    shown = grown;
                    exchange.onMarketData(Trial.mbp10(List.<long[]>of(new long[] {90, 50, 100, shown})));
                } else {
                    long oid = 1000L * seed + step + 1;
                    TimeInForce tif = action == 1 ? TimeInForce.IMMEDIATE_OR_CANCELED : TimeInForce.FILL_OR_KILL;
                    OrderType type = action == 3 ? OrderType.MARKET : OrderType.LIMIT;
                    for (OrderExecutionReport report : Immediate.submit(
                            exchange, makeOrder(100, 1 + random.nextInt(15), Side.Bid, oid, type, tif))) {
                        ExecType exec = report.decoder.execType();
                        if (exec == ExecType.FILL || exec == ExecType.PARTIAL_FILL) {
                            taken += report.decoder.filledQty();
                        }
                    }
                }
                if (taken > budget) {
                    fail("seed " + seed + " step " + step + ": took " + taken + " but the book only ever showed "
                            + budget);
                }
            }
        }
    }

    /**
     * For one passive order that is never touched again, fed the same market data, a queue model that assumes
     * cancels come from ahead of us can only fill it sooner: at every step, cumulative fills under optimistic are at
     * least those under probabilistic, which are at least those under risk-averse.
     */
    @Test
    void queueModelsFillAPassiveOrderInOrderOfOptimism() {
        for (int seed = 0; seed < TRIALS; seed++) {
            Random random = new Random(seed);
            List<Schema> events = new ArrayList<>();
            long mid = 100;
            for (int i = 0; i < 300; i++) {
                if (random.nextInt(3) == 0) {
                    Mbp10Schema trade = Trial.emptyMbp10(Action.Trade);
                    boolean buy = random.nextBoolean();
                    trade.encoder
                            .side(buy ? Side.Bid : Side.Ask)
                            .price(buy ? mid + 1 : mid)
                            .size(1 + random.nextInt(20));
                    events.add(trade);
                } else {
                    mid += random.nextInt(9) == 0 ? (random.nextBoolean() ? 1 : -1) : 0;
                    List<long[]> levels = new ArrayList<>();
                    for (int l = 0; l < 3; l++) {
                        levels.add(new long[] {mid - l, 1 + random.nextInt(40), mid + 1 + l, 1 + random.nextInt(40)});
                    }
                    events.add(Trial.mbp10(levels));
                }
            }
            long[] optimistic = replay(new OptimisticQueueModel(), events);
            long[] probabilistic = replay(new ProbabilisticQueueModel(0.5), events);
            long[] riskAverse = replay(new RiskAverseQueueModel(), events);
            for (int i = 0; i < events.size(); i++) {
                if (optimistic[i] < probabilistic[i] || probabilistic[i] < riskAverse[i]) {
                    fail("seed " + seed + " event " + i + ": filled optimistic=" + optimistic[i] + " probabilistic="
                            + probabilistic[i] + " risk-averse=" + riskAverse[i]);
                }
            }
        }
    }

    /** Rests a bid at 100 behind the opening book, replays {@code events}, and returns its cumulative fill after each. */
    private static long[] replay(QueueModel queueModel, List<Schema> events) {
        MbpSimulatedExchange exchange = new MbpSimulatedExchange(
                (price, quantity, isMaker) -> 0, new StaticLatency(0), new StaticLatency(0), queueModel);
        exchange.onMarketData(Trial.mbp10(List.<long[]>of(new long[] {100, 30, 101, 30})));
        Immediate.submit(exchange, makeOrder(100, 60, Side.Bid, 1L, OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED));
        long[] filled = new long[events.size()];
        long cumulative = 0;
        for (int i = 0; i < events.size(); i++) {
            for (OrderExecutionReport report : exchange.onMarketData(events.get(i))) {
                cumulative = Math.max(cumulative, report.decoder.cumulativeQty());
            }
            filled[i] = cumulative;
        }
        return filled;
    }

    /** Where a batch of reports came from, which decides whether its fills took or made liquidity. */
    private enum Source {
        ARRIVAL,
        PROCESSING,
        MARKET_DATA
    }

    /** What the test knows about one order from what it sent and the reports it got back. */
    private static final class Tracked {
        final Side side;
        final OrderType type;
        final Set<Long> allowedQty = new HashSet<>();
        final Set<Long> prices = new HashSet<>();
        long qty;
        long filled;
        boolean seenReport;
        boolean terminal;

        Tracked(Side side, OrderType type, long qty, long price) {
            this.side = side;
            this.type = type;
            this.qty = qty;
            allowedQty.add(qty);
            prices.add(price);
        }
    }

    private static final class Trial {
        private final int seed;
        private final Random random;
        private final MbpSimulatedExchange exchange;
        private final boolean mbp1;
        private final Map<Long, Tracked> orders = new HashMap<>();
        private final StringBuilder log = new StringBuilder();
        private long now;
        private long nextOid = 1;
        private int step;
        private long mid = 100;
        private List<long[]> lastSnapshot;
        // A linear fee in price x quantity, with a maker rebate as often as a maker charge.
        private final double makerRate;
        private final double takerRate;

        Trial(int seed) {
            this.seed = seed;
            this.random = new Random(seed);
            QueueModel queueModel =
                    switch (random.nextInt(3)) {
                        case 0 -> new OptimisticQueueModel();
                        case 1 -> new RiskAverseQueueModel();
                        default -> new ProbabilisticQueueModel(random.nextDouble());
                    };
            SelfTradePrevention stp =
                    random.nextBoolean() ? SelfTradePrevention.CANCEL_INCOMING : SelfTradePrevention.CANCEL_RESTING;
            long takerDelay = random.nextInt(4);
            long makerDelay = random.nextInt(2);
            this.mbp1 = random.nextInt(4) == 0;
            this.makerRate = random.nextDouble() * 0.04 - 0.02;
            this.takerRate = random.nextDouble() * 0.05;
            // Random processing times let a later message come due before an earlier one.
            LatencyModel processing = random.nextInt(4) == 0
                    ? new GaussianLatency(1.5, 1.5, seed)
                    : new MakerTakerLatencyModel(0, takerDelay, makerDelay);
            this.exchange = new MbpSimulatedExchange(
                    (price, quantity, isMaker) -> (isMaker ? makerRate : takerRate) * price * quantity,
                    new StaticLatency(0),
                    processing,
                    queueModel,
                    stp);
        }

        String run() throws Exception {
            for (step = 0; step < STEPS; step++) {
                now += random.nextInt(3);
                handle(exchange.processDue(now), Source.PROCESSING);
                int action = random.nextInt(100);
                if (action < 2) {
                    // Actions the simulator ignores must change nothing.
                    Mbp10Schema ignored =
                            emptyMbp10(new Action[] {Action.Clear, Action.Fill, Action.None}[random.nextInt(3)]);
                    if (!exchange.onMarketData(ignored).isEmpty()) {
                        fail(ctx("an ignored action produced reports"));
                    }
                } else if (action < 35) {
                    handle(exchange.onMarketData(snapshot()), Source.MARKET_DATA);
                } else if (action < 50) {
                    handle(exchange.onMarketData(trade()), Source.MARKET_DATA);
                } else if (action < 78) {
                    submit();
                } else if (action < 89) {
                    cancel();
                } else {
                    modify();
                }
                handle(exchange.processDue(now), Source.PROCESSING);
                if (exchange.nextDueNanos() <= now) {
                    fail(ctx("a message due at " + exchange.nextDueNanos() + " is still held at " + now));
                }
                checkBook();
            }
            handle(exchange.processDue(Long.MAX_VALUE), Source.PROCESSING);
            for (Map.Entry<Long, Tracked> entry : orders.entrySet()) {
                if (!entry.getValue().terminal) {
                    handle(exchange.cancelOrder(makeCancel(1, 1, entry.getKey()), now), Source.ARRIVAL);
                }
            }
            handle(exchange.processDue(Long.MAX_VALUE), Source.PROCESSING);
            for (Map.Entry<Long, Tracked> entry : orders.entrySet()) {
                if (!entry.getValue().terminal) {
                    fail(ctx("order " + entry.getKey() + " could not be ended"));
                }
            }
            return log.toString();
        }

        // --- actions ---

        private Schema snapshot() {
            List<long[]> levels;
            if (lastSnapshot != null && random.nextInt(5) == 0) {
                levels = lastSnapshot;
            } else {
                mid += random.nextInt(3) - 1;
                int shape = random.nextInt(20);
                // Empty, one-sided, and locked or crossed books all reach us from real feeds.
                int bidLevels = shape == 0 || shape == 1 ? 0 : 1 + random.nextInt(mbp1 ? 1 : 10);
                int askLevels = shape == 0 || shape == 2 ? 0 : 1 + random.nextInt(mbp1 ? 1 : 10);
                long spread = shape == 3 ? -random.nextInt(2) : 1 + random.nextInt(2);
                long step = random.nextInt(4) == 0 ? 2 : 1;
                levels = new ArrayList<>();
                for (int i = 0; i < Math.max(bidLevels, askLevels); i++) {
                    long bidPx = i < bidLevels ? mid - i * step : PRICE_NULL;
                    long askPx = i < askLevels ? mid + spread + i * step : PRICE_NULL;
                    // Now and then a level shows a price with no size.
                    long bidSz = random.nextInt(15) == 0 ? SIZE_NULL : 1 + random.nextInt(30);
                    long askSz = random.nextInt(15) == 0 ? SIZE_NULL : 1 + random.nextInt(30);
                    levels.add(new long[] {bidPx, bidSz, askPx, askSz});
                }
                lastSnapshot = levels;
            }
            Action action = new Action[] {Action.Add, Action.Cancel, Action.Modify}[random.nextInt(3)];
            if (mbp1) {
                return mbp1(levels.isEmpty() ? new long[] {PRICE_NULL, 0, PRICE_NULL, 0} : levels.get(0), action);
            }
            Mbp10Schema schema = mbp10(levels);
            schema.encoder.action(action);
            return schema;
        }

        private Schema trade() {
            boolean buyAggressor = random.nextBoolean();
            // Mostly at the touch, sometimes sweeping through several levels.
            long through = random.nextInt(4) == 0 ? random.nextInt(5) : 0;
            long price = buyAggressor ? mid + 1 + random.nextInt(2) + through : mid - random.nextInt(2) - through;
            long size = 1 + random.nextInt(through > 0 ? 80 : 25);
            Side aggressor = buyAggressor ? Side.Bid : Side.Ask;
            if (mbp1) {
                Mbp1Schema schema = mbp1(new long[] {PRICE_NULL, 0, PRICE_NULL, 0}, Action.Trade);
                schema.encoder.side(aggressor).price(price).size(size);
                return schema;
            }
            Mbp10Schema schema = emptyMbp10(Action.Trade);
            schema.encoder.side(aggressor).price(price).size(size);
            return schema;
        }

        private void submit() {
            Side side = random.nextBoolean() ? Side.Bid : Side.Ask;
            boolean market = random.nextInt(5) == 0;
            OrderType type = market ? OrderType.MARKET : OrderType.LIMIT;
            // Mostly near the touch; sometimes deep enough to sit out of view.
            long price =
                    market ? 0 : random.nextInt(8) == 0 ? mid - 12 + random.nextInt(25) : mid - 3 + random.nextInt(8);
            long size = 1 + random.nextInt(30);
            // Now and then an order the exchange must refuse on arrival.
            int invalid = random.nextInt(40);
            if (invalid == 0) {
                size = 0;
            } else if (invalid == 1 && !market) {
                price = 0;
            } else if (invalid == 2) {
                side = Side.None;
            }
            TimeInForce tif =
                    switch (random.nextInt(7)) {
                        case 0 -> TimeInForce.IMMEDIATE_OR_CANCELED;
                        case 1 -> TimeInForce.FILL_OR_KILL;
                        default -> TimeInForce.GOOD_TILL_CANCELED;
                    };
            boolean postOnly = random.nextInt(market ? 30 : 6) == 0;
            long oid = nextOid++;
            orders.put(oid, new Tracked(side, type, size, price));
            log.append("submit ")
                    .append(oid)
                    .append(' ')
                    .append(side)
                    .append(' ')
                    .append(type)
                    .append(' ')
                    .append(price)
                    .append('x')
                    .append(size)
                    .append(' ')
                    .append(tif)
                    .append('\n');
            var order = makeOrder(price, size, side, oid, type, tif);
            order.encoder.flags().postOnly(postOnly);
            log.append(postOnly ? "  (post-only)\n" : "");
            handle(exchange.submitOrder(order, now), Source.ARRIVAL);
        }

        private void cancel() {
            if (orders.isEmpty()) {
                return;
            }
            long oid = 1 + random.nextInt((int) (nextOid - 1) + 2);
            log.append("cancel ").append(oid).append('\n');
            handle(exchange.cancelOrder(makeCancel(1, 1, oid), now), Source.ARRIVAL);
        }

        private void modify() {
            if (orders.isEmpty()) {
                return;
            }
            long oid = 1 + random.nextInt((int) (nextOid - 1));
            Tracked tracked = orders.get(oid);
            long newPrice = mid - 3 + random.nextInt(8);
            long newSize = 1 + random.nextInt(35);
            if (tracked != null && random.nextInt(5) == 0) {
                // An amend that changes only one thing, or nothing.
                if (random.nextBoolean()) {
                    newPrice = tracked.prices.iterator().next();
                } else {
                    newSize = tracked.qty;
                }
            }
            if (tracked != null && tracked.type == OrderType.LIMIT) {
                tracked.allowedQty.add(newSize);
                tracked.prices.add(newPrice);
            }
            log.append("modify ")
                    .append(oid)
                    .append(' ')
                    .append(newPrice)
                    .append('x')
                    .append(newSize)
                    .append('\n');
            handle(exchange.modifyOrder(makeModify(1, 1, oid, newPrice, newSize), now), Source.ARRIVAL);
        }

        // --- report checks ---

        private void handle(List<OrderExecutionReport> reports, Source source) {
            for (OrderExecutionReport report : reports) {
                checkFeeAndLiquidity(report, source);
                check(report);
            }
        }

        private void check(OrderExecutionReport report) {
            long oid = report.getClientOidCounter();
            ExecType exec = report.decoder.execType();
            long cumulative = report.decoder.cumulativeQty();
            long leaves = report.decoder.leavesQty();
            log.append("  ")
                    .append(oid)
                    .append(' ')
                    .append(exec)
                    .append(" filled=")
                    .append(report.decoder.filledQty())
                    .append(" px=")
                    .append(report.decoder.fillPrice())
                    .append(" cum=")
                    .append(cumulative)
                    .append(" leaves=")
                    .append(leaves)
                    .append('\n');

            Tracked tracked = orders.get(oid);
            if (tracked == null) {
                if (exec != ExecType.CANCEL_REJECT) {
                    fail(ctx("report " + exec + " for an order never sent: " + oid));
                }
                return;
            }
            if (tracked.terminal) {
                if (exec != ExecType.CANCEL_REJECT) {
                    fail(ctx(exec + " for order " + oid + " after it ended"));
                }
                return;
            }
            switch (exec) {
                case NEW -> {
                    if (!tracked.allowedQty.contains(leaves + cumulative) || cumulative != tracked.filled) {
                        fail(ctx("NEW for " + oid + " with cum=" + cumulative + " leaves=" + leaves + " but filled="
                                + tracked.filled + " allowed qty=" + tracked.allowedQty));
                    }
                    tracked.qty = leaves + cumulative;
                }
                case PARTIAL_FILL, FILL -> checkFill(tracked, oid, report, exec);
                case CANCEL -> {
                    if (cumulative != tracked.filled) {
                        fail(ctx("CANCEL for " + oid + " reports cum " + cumulative + " but it filled "
                                + tracked.filled));
                    }
                    tracked.terminal = true;
                }
                case REJECT -> {
                    if (tracked.seenReport || tracked.filled > 0) {
                        fail(ctx("REJECT for " + oid + " after it was already working"));
                    }
                    tracked.terminal = true;
                }
                case CANCEL_REJECT -> {
                    // Refuses a cancel or modify; it says nothing about whether the order itself is working yet.
                    return;
                }
                default -> fail(ctx("unexpected exec type " + exec));
            }
            tracked.seenReport = true;
        }

        /**
         * A fill made while processing an order took liquidity; one made by market data was resting. Fees follow the
         * fee model exactly for a maker fill. A taker fill reports an average price over the levels it took, so its
         * fee is checked against the range that average allows.
         */
        private void checkFeeAndLiquidity(OrderExecutionReport report, Source source) {
            ExecType exec = report.decoder.execType();
            long fee = report.decoder.fee();
            boolean isFill = exec == ExecType.FILL || exec == ExecType.PARTIAL_FILL;
            if (!isFill) {
                if (fee != 0 && fee != Mbp10Decoder.priceNullValue()) {
                    fail(ctx(exec + " for " + report.getClientOidCounter() + " carries fee " + fee));
                }
                return;
            }
            long qty = report.decoder.filledQty();
            long price = report.decoder.fillPrice();
            Liquidity liquidity = report.decoder.liquidity();
            switch (source) {
                case ARRIVAL -> fail(ctx("fill returned on arrival for " + report.getClientOidCounter()));
                case MARKET_DATA -> {
                    if (liquidity != Liquidity.MAKER) {
                        fail(ctx("resting fill for " + report.getClientOidCounter() + " flagged " + liquidity));
                    }
                    long expected = (long) (makerRate * price * qty);
                    if (fee != expected) {
                        fail(ctx("maker fee " + fee + " for " + qty + "@" + price + ", expected " + expected));
                    }
                }
                case PROCESSING -> {
                    if (liquidity != Liquidity.TAKER) {
                        fail(ctx("taking fill for " + report.getClientOidCounter() + " flagged " + liquidity));
                    }
                    double low = takerRate * price * qty;
                    double high = takerRate * (price + 1) * qty;
                    if (fee < (long) low - 1 || fee > (long) high + 1) {
                        fail(ctx("taker fee " + fee + " for " + qty + " @ avg " + price + " outside [" + low + ", "
                                + high + "]"));
                    }
                }
            }
        }

        private void checkFill(Tracked tracked, long oid, OrderExecutionReport report, ExecType exec) {
            long filledQty = report.decoder.filledQty();
            long cumulative = report.decoder.cumulativeQty();
            long leaves = report.decoder.leavesQty();
            long price = report.decoder.fillPrice();
            if (filledQty <= 0) {
                fail(ctx("fill of " + filledQty + " for " + oid));
            }
            if (cumulative != tracked.filled + filledQty) {
                fail(ctx("cum " + cumulative + " for " + oid + " but filled " + tracked.filled + " + " + filledQty));
            }
            // A fill may be the first word on a modified order; take the quantity it implies if that was asked for.
            if (cumulative + leaves != tracked.qty) {
                if (!tracked.allowedQty.contains(cumulative + leaves)) {
                    fail(ctx("cum " + cumulative + " + leaves " + leaves + " for " + oid + " is not a quantity it had: "
                            + tracked.allowedQty));
                }
                tracked.qty = cumulative + leaves;
            }
            if (leaves < 0 || (exec == ExecType.FILL) != (leaves == 0)) {
                fail(ctx(exec + " for " + oid + " with leaves " + leaves));
            }
            if (tracked.type == OrderType.LIMIT) {
                long best = tracked.side == Side.Bid
                        ? tracked.prices.stream().max(Long::compare).orElseThrow()
                        : tracked.prices.stream().min(Long::compare).orElseThrow();
                if (tracked.side == Side.Bid ? price > best : price < best) {
                    fail(ctx(tracked.side + " " + oid + " filled at " + price + " beyond its limit " + best));
                }
            }
            if (report.decoder.liquidity() == Liquidity.MAKER && !tracked.prices.contains(price)) {
                fail(ctx("resting " + oid + " filled at " + price + ", not a price it rested at: " + tracked.prices));
            }
            tracked.filled = cumulative;
            if (exec == ExecType.FILL) {
                tracked.terminal = true;
            }
        }

        // --- book checks ---

        private void checkBook() throws Exception {
            Object book = field(exchange, "orderBook");
            TreeMap<Long, OrderBookLevel> bids = call(book, "bids");
            TreeMap<Long, OrderBookLevel> asks = call(book, "asks");
            Map<Long, LocalOrder> localBids = call(book, "localBidOrders");
            Map<Long, LocalOrder> localAsks = call(book, "localAskOrders");
            checkSide(bids, localBids, "bid");
            checkSide(asks, localAsks, "ask");

            long bestOwnBid = localBids.values().stream()
                    .mapToLong(lo -> lo.order.decoder.price())
                    .max()
                    .orElse(Long.MIN_VALUE);
            long bestOwnAsk = localAsks.values().stream()
                    .mapToLong(lo -> lo.order.decoder.price())
                    .min()
                    .orElse(Long.MAX_VALUE);
            if (bestOwnBid >= bestOwnAsk) {
                fail(ctx("our own resting orders cross: bid " + bestOwnBid + " >= ask " + bestOwnAsk));
            }
        }

        private void checkSide(TreeMap<Long, OrderBookLevel> levels, Map<Long, LocalOrder> locals, String side) {
            int inLevels = 0;
            for (Map.Entry<Long, OrderBookLevel> entry : levels.entrySet()) {
                OrderBookLevel level = entry.getValue();
                if (level.size < 0 || level.consumed < 0) {
                    fail(ctx(
                            side + " level " + entry.getKey() + " size=" + level.size + " consumed=" + level.consumed));
                }
                for (LocalOrder lo : level.localOrders) {
                    inLevels++;
                    long oid = lo.order.getClientOidCounter();
                    if (lo.remaining <= 0 || lo.phantomVolume < 0 || lo.order.decoder.price() != entry.getKey()) {
                        fail(ctx(side + " order " + oid + " remaining=" + lo.remaining + " phantom=" + lo.phantomVolume
                                + " price=" + lo.order.decoder.price() + " at level " + entry.getKey()));
                    }
                    if (locals.get(oid) != lo) {
                        fail(ctx(side + " order " + oid + " is on a level but not in the order map"));
                    }
                    Tracked tracked = orders.get(oid);
                    if (tracked == null || tracked.terminal) {
                        fail(ctx(side + " order " + oid + " rests on the book after it ended"));
                    }
                    if (lo.order.decoder.size() - lo.remaining != tracked.filled) {
                        fail(ctx(side + " order " + oid + " book filled " + (lo.order.decoder.size() - lo.remaining)
                                + " but reports say " + tracked.filled));
                    }
                }
            }
            if (inLevels != locals.size()) {
                fail(ctx(side + " order map has " + locals.size() + " orders but levels hold " + inLevels));
            }
        }

        private String ctx(String message) {
            String tail = log.length() > 4000 ? log.substring(log.length() - 4000) : log.toString();
            return "seed " + seed + " step " + step + ": " + message + "\n--- recent ---\n" + tail;
        }

        // --- schema builders ---

        private static Mbp10Schema mbp10(List<long[]> levels) {
            Mbp10Schema schema = emptyMbp10(Action.Add);
            var e = schema.encoder;
            for (int i = 0; i < levels.size(); i++) {
                long[] l = levels.get(i);
                long bidSz = l[0] == PRICE_NULL ? SIZE_NULL : l[1];
                long askSz = l[2] == PRICE_NULL ? SIZE_NULL : l[3];
                switch (i) {
                    case 0 -> e.bidPrice0(l[0]).bidSize0(bidSz).askPrice0(l[2]).askSize0(askSz);
                    case 1 -> e.bidPrice1(l[0]).bidSize1(bidSz).askPrice1(l[2]).askSize1(askSz);
                    case 2 -> e.bidPrice2(l[0]).bidSize2(bidSz).askPrice2(l[2]).askSize2(askSz);
                    case 3 -> e.bidPrice3(l[0]).bidSize3(bidSz).askPrice3(l[2]).askSize3(askSz);
                    case 4 -> e.bidPrice4(l[0]).bidSize4(bidSz).askPrice4(l[2]).askSize4(askSz);
                    case 5 -> e.bidPrice5(l[0]).bidSize5(bidSz).askPrice5(l[2]).askSize5(askSz);
                    case 6 -> e.bidPrice6(l[0]).bidSize6(bidSz).askPrice6(l[2]).askSize6(askSz);
                    case 7 -> e.bidPrice7(l[0]).bidSize7(bidSz).askPrice7(l[2]).askSize7(askSz);
                    case 8 -> e.bidPrice8(l[0]).bidSize8(bidSz).askPrice8(l[2]).askSize8(askSz);
                    default -> e.bidPrice9(l[0]).bidSize9(bidSz).askPrice9(l[2]).askSize9(askSz);
                }
            }
            return schema;
        }

        private static Mbp1Schema mbp1(long[] l, Action action) {
            Mbp1Schema schema = new Mbp1Schema();
            schema.encoder.action(action).side(Side.None).price(PRICE_NULL).size(SIZE_NULL);
            schema.encoder
                    .bidPrice0(l[0])
                    .bidSize0(l[0] == PRICE_NULL ? SIZE_NULL : l[1])
                    .askPrice0(l[2])
                    .askSize0(l[2] == PRICE_NULL ? SIZE_NULL : l[3]);
            return schema;
        }

        private static Mbp10Schema emptyMbp10(Action action) {
            Mbp10Schema schema = new Mbp10Schema();
            var e = schema.encoder;
            e.action(action).side(Side.None).price(PRICE_NULL).size(SIZE_NULL);
            e.bidPrice0(PRICE_NULL).bidSize0(SIZE_NULL).askPrice0(PRICE_NULL).askSize0(SIZE_NULL);
            e.bidPrice1(PRICE_NULL).bidSize1(SIZE_NULL).askPrice1(PRICE_NULL).askSize1(SIZE_NULL);
            e.bidPrice2(PRICE_NULL).bidSize2(SIZE_NULL).askPrice2(PRICE_NULL).askSize2(SIZE_NULL);
            e.bidPrice3(PRICE_NULL).bidSize3(SIZE_NULL).askPrice3(PRICE_NULL).askSize3(SIZE_NULL);
            e.bidPrice4(PRICE_NULL).bidSize4(SIZE_NULL).askPrice4(PRICE_NULL).askSize4(SIZE_NULL);
            e.bidPrice5(PRICE_NULL).bidSize5(SIZE_NULL).askPrice5(PRICE_NULL).askSize5(SIZE_NULL);
            e.bidPrice6(PRICE_NULL).bidSize6(SIZE_NULL).askPrice6(PRICE_NULL).askSize6(SIZE_NULL);
            e.bidPrice7(PRICE_NULL).bidSize7(SIZE_NULL).askPrice7(PRICE_NULL).askSize7(SIZE_NULL);
            e.bidPrice8(PRICE_NULL).bidSize8(SIZE_NULL).askPrice8(PRICE_NULL).askSize8(SIZE_NULL);
            e.bidPrice9(PRICE_NULL).bidSize9(SIZE_NULL).askPrice9(PRICE_NULL).askSize9(SIZE_NULL);
            return schema;
        }

        private static Object field(Object target, String name) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }

        @SuppressWarnings("unchecked")
        private static <T> T call(Object target, String method) throws Exception {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            return (T) m.invoke(target);
        }
    }
}
