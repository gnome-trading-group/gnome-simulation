package group.gnometrading.simulation.book;

import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A stretch of a Polymarket International match as a live session recorded it: both outcomes' books and prints, and
 * the session's own bids on one of them. Prices are in price units (1e9 to the dollar), sizes in size units (1e6 to
 * the contract), as recorded.
 */
final class RecordedPolymarketFeed {

    private static final int LEVELS = 10;
    private static final long PRICE_NULL = Long.MIN_VALUE;

    record Recorded(
            boolean ours, long nanos, boolean trade, boolean buy, long price, long size, List<BidAskLevel> levels) {}

    private record OurOrder(boolean add, long nanos, long oid, long price, long size) {}

    final List<Recorded> records = new ArrayList<>();
    private final List<OurOrder> orders = new ArrayList<>();

    /** Loads {@code /book/polymarket_intl/<name>.csv.gz} and its {@code -orders.csv}. */
    RecordedPolymarketFeed(String name) {
        try {
            load(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void load(String name) throws IOException {
        try (BufferedReader reader = reader("/book/polymarket_intl/" + name + ".csv.gz", true)) {
            List<String> header = Arrays.asList(reader.readLine().split(","));
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                records.add(parse(header, line.split(",", -1)));
            }
        }
        Map<String, Long> oids = new HashMap<>();
        try (BufferedReader reader = reader("/book/polymarket_intl/" + name + "-orders.csv", false)) {
            reader.readLine();
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                String[] f = line.split(",", -1);
                long oid = oids.computeIfAbsent(f[2], k -> (long) oids.size() + 1);
                boolean add = f[0].equals("new");
                orders.add(new OurOrder(
                        add,
                        Long.parseLong(f[1]),
                        oid,
                        add ? (long) Double.parseDouble(f[3]) : 0,
                        add ? (long) Double.parseDouble(f[4]) : 0));
            }
        }
    }

    private static BufferedReader reader(String resource, boolean gzipped) throws IOException {
        InputStream in = RecordedPolymarketFeed.class.getResourceAsStream(resource);
        return new BufferedReader(
                new InputStreamReader(gzipped ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8));
    }

    private static Recorded parse(List<String> header, String[] f) {
        boolean ours = f[header.indexOf("outcome")].equals("ours");
        long nanos = Long.parseLong(f[header.indexOf("tev")]);
        if (f[header.indexOf("action")].equals("T")) {
            return new Recorded(
                    ours,
                    nanos,
                    true,
                    f[header.indexOf("side")].equals("B"),
                    Long.parseLong(f[header.indexOf("price")]),
                    Long.parseLong(f[header.indexOf("size")]),
                    List.of());
        }
        List<BidAskLevel> levels = new ArrayList<>();
        for (int i = 0; i < LEVELS; i++) {
            long bidPx = Long.parseLong(f[header.indexOf("bp" + i)]);
            long bidSz = size(f[header.indexOf("bs" + i)]);
            long askPx = Long.parseLong(f[header.indexOf("ap" + i)]);
            long askSz = size(f[header.indexOf("as" + i)]);
            if (bidPx != PRICE_NULL || askPx != PRICE_NULL) {
                levels.add(new BidAskLevel(
                        bidPx, bidPx == PRICE_NULL ? 0 : bidSz, askPx, askPx == PRICE_NULL ? 0 : askSz));
            }
        }
        return new Recorded(ours, nanos, false, false, 0, 0, levels);
    }

    private static long size(String field) {
        long value = Long.parseLong(field);
        return value < 0 || value == Long.MAX_VALUE ? 0 : value;
    }

    private static Order bid(long oid, long price, long size) {
        Order order = new Order();
        order.encoder
                .exchangeId((short) 1)
                .securityId(1)
                .price(price)
                .size(size)
                .side(Side.Bid)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encodeClientOid(oid, 0);
        return order;
    }

    private static group.gnometrading.schemas.book.Side side(boolean buy) {
        return buy ? group.gnometrading.schemas.book.Side.Bid : group.gnometrading.schemas.book.Side.Ask;
    }

    /**
     * Our fills, as (price, size) pairs in order, replaying both outcomes with our live orders placed and cancelled when
     * the session sent them. {@code mirror}: a print on the other outcome also reaches our book at {@code 1 - price},
     * from the other side, as the backtest driver routes it.
     */
    List<long[]> replay(TradePairing pairing, boolean mirror, boolean sweepingPrints) {
        MbpBook ours = new MbpBook(new RiskAverseQueueModel(), pairing, sweepingPrints);
        MbpBook other = new MbpBook(new RiskAverseQueueModel(), pairing, sweepingPrints);
        List<long[]> fills = new ArrayList<>();
        int nextOrder = 0;
        for (Recorded r : records) {
            while (nextOrder < orders.size() && orders.get(nextOrder).nanos() <= r.nanos()) {
                OurOrder o = orders.get(nextOrder++);
                if (o.add()) {
                    ours.addLocalOrder(bid(o.oid(), o.price(), o.size()));
                } else {
                    ours.cancelOrder(o.oid());
                }
            }
            MbpBook book = r.ours() ? ours : other;
            List<LocalOrderFill> filled = r.trade()
                    ? book.onTrade(r.price(), r.size(), side(r.buy()), r.nanos())
                    : book.onMarketUpdate(r.levels(), LEVELS, r.nanos());
            List<LocalOrderFill> mirrored = mirror && r.trade() && !r.ours()
                    ? ours.onTrade(Statics.PRICE_SCALING_FACTOR - r.price(), r.size(), side(!r.buy()), r.nanos())
                    : List.of();
            for (List<LocalOrderFill> each : List.of(filled, mirrored)) {
                for (LocalOrderFill fill : each) {
                    fills.add(new long[] {fill.localOrder().order.decoder.price(), fill.fillSize()});
                }
            }
        }
        return fills;
    }
}
