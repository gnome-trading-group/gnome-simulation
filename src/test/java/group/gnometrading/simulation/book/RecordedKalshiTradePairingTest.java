package group.gnometrading.simulation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * An hour of a Kalshi Rotten Tomatoes strike as collection recorded it, book records and trades (KXRT-EBE-50,
 * 2026-11-12 17:00-18:00 UTC: 25k records, 252 trades). Kalshi stamps a trade and its book change with the same
 * millisecond but delivers either first, so replaying each millisecond with its trades moved behind its book records is
 * the same market. Moving them ahead is not: a few trades' book changes are stamped a millisecond later, and a trade
 * moved ahead of book records that don't show it yet would be read twice. With pairing, our resting orders' fills don't
 * change when the trades are moved behind, and the fills the double count gave us are gone.
 *
 * <p>Prices are in cents and sizes in hundredths of a contract, as exported.
 */
class RecordedKalshiTradePairingTest {

    private static final String RESOURCE = "/book/kalshi/KXRT-EBE-50-2026-11-12T17.csv.gz";
    private static final int LEVELS = 10;
    private static final long PRICE_NULL = Long.MIN_VALUE;
    private static final long QUOTE_SIZE = 500 * 100;

    private record Recorded(long nanos, boolean trade, boolean buy, long price, long size, List<BidAskLevel> levels) {}

    private static List<List<Recorded>> millis;

    @BeforeAll
    static void load() throws IOException {
        List<Recorded> records = new ArrayList<>();
        try (InputStream in = RecordedKalshiTradePairingTest.class.getResourceAsStream(RESOURCE);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                records.add(parse(line.split(",", -1)));
            }
        }
        millis = new ArrayList<>();
        for (Recorded record : records) {
            if (millis.isEmpty() || millis.get(millis.size() - 1).get(0).nanos() != record.nanos()) {
                millis.add(new ArrayList<>());
            }
            millis.get(millis.size() - 1).add(record);
        }
    }

    private static Recorded parse(String[] f) {
        long nanos = Long.parseLong(f[0]);
        if (f[1].equals("T")) {
            return new Recorded(nanos, true, f[2].equals("B"), Long.parseLong(f[3]), Long.parseLong(f[4]), List.of());
        }
        List<BidAskLevel> levels = new ArrayList<>();
        for (int i = 0; i < LEVELS; i++) {
            long bidPx = value(f[5 + i], PRICE_NULL);
            long bidSz = value(f[5 + LEVELS + i], 0);
            long askPx = value(f[5 + 2 * LEVELS + i], PRICE_NULL);
            long askSz = value(f[5 + 3 * LEVELS + i], 0);
            if (bidPx != PRICE_NULL || askPx != PRICE_NULL) {
                levels.add(new BidAskLevel(bidPx, bidSz, askPx, askSz));
            }
        }
        return new Recorded(nanos, false, false, 0, 0, levels);
    }

    private static long value(String field, long missing) {
        return field.isEmpty() ? missing : Long.parseLong(field);
    }

    /** Fills to each of our orders, by client OID, from quoting the touch on both sides for the whole hour. */
    private static Map<Long, Long> replay(TradePairing pairing, boolean tradesLast) {
        MbpBook book = new MbpBook(new RiskAverseQueueModel(), pairing);
        Map<Long, Long> fills = new HashMap<>();
        long[] nextOid = {1};
        Long bid = null;
        Long ask = null;
        for (List<Recorded> milli : millis) {
            List<Recorded> ordered = new ArrayList<>(milli);
            if (tradesLast) {
                ordered.sort((a, b) -> Boolean.compare(a.trade(), b.trade()));
            }
            for (Recorded r : ordered) {
                List<LocalOrderFill> filled = r.trade()
                        ? book.onTrade(
                                r.price(),
                                r.size(),
                                r.buy()
                                        ? group.gnometrading.schemas.book.Side.Bid
                                        : group.gnometrading.schemas.book.Side.Ask,
                                r.nanos())
                        : book.onMarketUpdate(r.levels(), LEVELS, r.nanos());
                for (LocalOrderFill fill : filled) {
                    fills.merge(fill.localOrder().order.getClientOidCounter(), fill.fillSize(), Long::sum);
                }
            }
            bid = requote(book, Side.Bid, bid, nextOid);
            ask = requote(book, Side.Ask, ask, nextOid);
        }
        return fills;
    }

    /** Keeps one order resting at the touch: a new one when the last filled away or the touch moved off it. */
    private static Long requote(MbpBook book, Side side, Long oid, long[] nextOid) {
        Long touch = side == Side.Bid ? book.getBestBid() : book.getBestAsk();
        LocalOrder resting = oid == null ? null : book.findLocalOrder(oid);
        if (touch == null || (resting != null && resting.order.decoder.price() == touch)) {
            return oid;
        }
        if (resting != null) {
            book.cancelOrder(oid);
        }
        long next = nextOid[0]++;
        Order order = new Order();
        order.encoder
                .exchangeId((short) 1)
                .securityId(1)
                .price(touch)
                .size(QUOTE_SIZE)
                .side(side)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encodeClientOid(next, 0);
        book.addLocalOrder(order);
        return next;
    }

    private static long total(Map<Long, Long> fills) {
        return fills.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    void theFixtureHasTradesWhoseBookChangeArrivedFirst() {
        long trades =
                millis.stream().flatMap(List::stream).filter(Recorded::trade).count();
        long bookFirst = millis.stream()
                .filter(m -> !m.get(0).trade() && m.stream().anyMatch(Recorded::trade))
                .count();
        assertEquals(252, trades);
        assertTrue(bookFirst > 20, "milliseconds whose book record came before their trade: " + bookFirst);
    }

    @Test
    void withPairingOurFillsAreTheSameWithEveryTradeArrivingAfterItsBookChange() {
        Map<Long, Long> asRecorded = replay(TradePairing.SAME_TIMESTAMP, false);
        Map<Long, Long> tradesLast = replay(TradePairing.SAME_TIMESTAMP, true);
        assertTrue(total(asRecorded) > 0, "the hour fills our quotes");
        assertEquals(asRecorded, tradesLast);
    }

    @Test
    void pairingTakesBackTheFillsTheDoubleCountGaveUs() {
        long paired = total(replay(TradePairing.SAME_TIMESTAMP, false));
        long unpaired = total(replay(TradePairing.OFF, false));
        assertTrue(paired < unpaired, "paired " + paired + " against unpaired " + unpaired);
    }
}
