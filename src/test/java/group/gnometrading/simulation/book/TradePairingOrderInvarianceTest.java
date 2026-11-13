package group.gnometrading.simulation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.queues.OptimisticQueueModel;
import group.gnometrading.simulation.queues.ProbabilisticQueueModel;
import group.gnometrading.simulation.queues.QueueModel;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Random sequences of joins, trades, cancels and growth at one level, replayed twice: with each trade's book change
 * delivered before its trade record, and after it. With pairing, both give the same fills to every one of our orders.
 *
 * <p>The queue ahead of our orders matches too, except after we are filled: the trade-first replay then reads the
 * volume filled to us as a cancel when the book catches up, and models that let cancels move us up diverge by that
 * much. Risk-averse cancels never move us up, so it matches throughout.
 */
class TradePairingOrderInvarianceTest {

    private static final long PRICE = 47;
    private static final long SECOND = 1_000_000_000L;
    private static final long FIFTY_MILLIS = 50_000_000L;
    private static final int RUNS = 3000;
    private static final group.gnometrading.schemas.book.Side BUY = group.gnometrading.schemas.book.Side.Bid;

    /** Where the venue stamps a trade's book change relative to the trade, for each kind of feed paired. */
    enum Feed {
        SAME_STAMP(TradePairing.SAME_TIMESTAMP, 0, 0),
        STAMPED_BEFORE(TradePairing.within(Duration.ofMillis(50), Duration.ZERO), -FIFTY_MILLIS, 0),
        STAMPED_AFTER(TradePairing.within(Duration.ZERO, Duration.ofMillis(50)), 0, FIFTY_MILLIS);

        final TradePairing pairing;
        final long minOffset;
        final long maxOffset;

        Feed(TradePairing pairing, long minOffset, long maxOffset) {
            this.pairing = pairing;
            this.minOffset = minOffset;
            this.maxOffset = maxOffset;
        }

        long offset(Random random) {
            return minOffset + (long) (random.nextDouble() * (maxOffset - minOffset));
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        List<Object[]> models = List.of(
                new Object[] {"risk averse", (Supplier<QueueModel>) RiskAverseQueueModel::new, true},
                new Object[] {"optimistic", (Supplier<QueueModel>) OptimisticQueueModel::new, false},
                new Object[] {
                    "probabilistic 0.25", (Supplier<QueueModel>) () -> new ProbabilisticQueueModel(0.25), false
                },
                new Object[] {"probabilistic 0.5", (Supplier<QueueModel>) () -> new ProbabilisticQueueModel(0.5), false
                },
                new Object[] {"probabilistic 0.9", (Supplier<QueueModel>) () -> new ProbabilisticQueueModel(0.9), false
                });
        for (Object[] model : models) {
            for (Feed feed : Feed.values()) {
                out.add(Arguments.of(model[0], model[1], model[2], feed));
            }
        }
        return out.stream();
    }

    private static Order ask(long size, long clientOid) {
        Order order = new Order();
        order.encoder
                .exchangeId((short) 1)
                .securityId(1)
                .price(PRICE)
                .size(size)
                .side(Side.Ask)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encodeClientOid(clientOid, 0);
        return order;
    }

    private static List<BidAskLevel> top(long askSize) {
        return List.of(new BidAskLevel(45, 100, PRICE, askSize));
    }

    @ParameterizedTest(name = "{0}, {3}")
    @MethodSource("cases")
    void ourFillsDoNotDependOnWhetherTheBookOrTheTradeArrivesFirst(
            String name, Supplier<QueueModel> model, boolean cancelsNeverMoveUsUp, Feed feed) {
        long pairedGroups = 0;
        long filledRuns = 0;
        for (int run = 0; run < RUNS; run++) {
            Random random = new Random(run);
            MbpBook bookFirst = new MbpBook(model.get(), feed.pairing);
            MbpBook tradeFirst = new MbpBook(model.get(), feed.pairing);
            long level = 1 + random.nextInt(200);
            bookFirst.onMarketUpdate(top(level), 10, 0);
            tradeFirst.onMarketUpdate(top(level), 10, 0);
            List<Long> ours = new ArrayList<>();
            long nextOid = 1;
            int groups = 3 + random.nextInt(12);

            for (int g = 1; g <= groups; g++) {
                String where = name + " " + feed + " run " + run + " group " + g;
                long tradeNanos = g * SECOND;
                if (random.nextInt(3) == 0) {
                    long oid = nextOid++;
                    long size = 1 + random.nextInt(60);
                    bookFirst.addLocalOrder(ask(size, oid));
                    tradeFirst.addLocalOrder(ask(size, oid));
                    ours.add(oid);
                }
                if (random.nextInt(4) == 0) {
                    level += random.nextInt(50);
                    bookFirst.onMarketUpdate(top(level), 10, tradeNanos);
                    tradeFirst.onMarketUpdate(top(level), 10, tradeNanos);
                    continue;
                }

                int count = random.nextInt(4);
                long[] trades = new long[count];
                long traded = 0;
                for (int i = 0; i < count && traded < level; i++) {
                    trades[i] = 1 + random.nextInt((int) Math.min(60, level - traded));
                    traded += trades[i];
                }
                long cancelled = level - traded > 0 ? random.nextInt((int) (level - traded) + 1) : 0;
                long after = level - traded - cancelled;
                boolean onePerTrade = random.nextBoolean();

                List<LocalOrderFill> fillsBookFirst = new ArrayList<>();
                long shown = level - cancelled;
                if (!onePerTrade) {
                    bookFirst.onMarketUpdate(top(after), 10, tradeNanos + feed.offset(random));
                }
                for (long trade : trades) {
                    if (trade == 0) {
                        continue;
                    }
                    if (onePerTrade) {
                        shown -= trade;
                        bookFirst.onMarketUpdate(top(shown), 10, tradeNanos + feed.offset(random));
                    }
                    fillsBookFirst.addAll(bookFirst.onTrade(PRICE, trade, BUY, tradeNanos));
                }
                if (onePerTrade) {
                    bookFirst.onMarketUpdate(top(after), 10, tradeNanos + feed.offset(random));
                }

                List<LocalOrderFill> fillsTradeFirst = new ArrayList<>();
                for (long trade : trades) {
                    if (trade > 0) {
                        fillsTradeFirst.addAll(tradeFirst.onTrade(PRICE, trade, BUY, tradeNanos));
                    }
                }
                tradeFirst.onMarketUpdate(top(after), 10, tradeNanos + feed.offset(random));
                level = after;
                pairedGroups += traded > 0 ? 1 : 0;

                for (long oid : ours) {
                    assertEquals(
                            filledFor(fillsTradeFirst, oid), filledFor(fillsBookFirst, oid), where + " fills " + oid);
                }
                boolean weFilled = !fillsTradeFirst.isEmpty();
                if (!weFilled || cancelsNeverMoveUsUp) {
                    for (long oid : ours) {
                        LocalOrder a = bookFirst.findLocalOrder(oid);
                        LocalOrder b = tradeFirst.findLocalOrder(oid);
                        assertEquals(b == null, a == null, where + " order " + oid + " still resting");
                        if (a != null) {
                            assertEquals(b.phantomVolume, a.phantomVolume, where + " queue ahead of " + oid);
                            assertEquals(b.remaining, a.remaining, where + " remaining " + oid);
                        }
                    }
                }
                if (weFilled) {
                    filledRuns++;
                    if (!cancelsNeverMoveUsUp) {
                        break;
                    }
                }
            }
        }
        assertEquals(true, pairedGroups > RUNS, "the runs exercise trades: " + pairedGroups);
        assertEquals(true, filledRuns > RUNS / 20, "the runs exercise our fills: " + filledRuns);
    }

    private static long filledFor(List<LocalOrderFill> fills, long clientOid) {
        return fills.stream()
                .filter(f -> f.localOrder().order.getClientOidCounter() == clientOid)
                .mapToLong(LocalOrderFill::fillSize)
                .sum();
    }
}
