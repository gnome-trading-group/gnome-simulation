package group.gnometrading.simulation.rewards;

import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.sm.incentive.IncentiveProgram;
import group.gnometrading.sm.incentive.IncentiveTimeline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Estimates what our resting orders earn from venues' liquidity programs, from the books and our orders as they
 * change. A venue samples its book at random moments; between two events the book and our orders are constant, so
 * integrating our share over time gives the expected reward of those samples.
 *
 * <p>Listings are bound to markets up front. A binary market is a plain listing and its complement (Kalshi YES and
 * NO): both are scored as one market, in the plain listing's terms, from one canonical book (the plain listing's when
 * it is bound), so nothing is scored twice. Each event is taken at the latest time seen, so time never runs backwards.
 *
 * <p>Venue rules live in each market's {@link LiquidityRule}. After binding, nothing allocates.
 */
public final class RewardScorer {

    /** How our orders relate to the books the scorer is given. */
    public enum Mode {
        /** The books are the venue's without our orders (backtests and paper): ours are added to them. */
        INSERT_OURS,
        /** The books already hold our orders (live): ours are taken out to find everyone else's. */
        OURS_IN_BOOK
    }

    static final int LEVELS = 10;
    private static final long CERTAIN = 1_000_000_000L;
    private static final double SIZE_SCALE = 1_000_000.0;
    private static final int NONE = -1;

    private final Mode mode;
    private final int ordersPerMarket;
    private final Map<String, Market> marketsByKey = new HashMap<>();
    private final List<Market> markets = new ArrayList<>();
    private final List<RewardAccrual> accruals = new ArrayList<>();
    private final Int2ObjectHashMap<Binding> listings = new Int2ObjectHashMap<>();
    private final Long2ObjectHashMap<OurOrder> orders = new Long2ObjectHashMap<>();
    private final OurOrder[] free;
    private int freeCount;
    private final long[] bidPrices = new long[LEVELS];
    private final long[] bidSizes = new long[LEVELS];
    private final long[] askPrices = new long[LEVELS];
    private final long[] askSizes = new long[LEVELS];
    private long clock = Long.MIN_VALUE;
    private long ordersDropped;

    /**
     * {@code ordersPerMarket} bounds our resting orders on one market and {@code orders} all of them; past either, a
     * market scores nothing (as if its data were lost) rather than allocating.
     */
    public RewardScorer(final Mode mode, final int ordersPerMarket, final int orders) {
        this.mode = mode;
        this.ordersPerMarket = ordersPerMarket;
        this.free = new OurOrder[orders];
        for (int i = 0; i < orders; i++) {
            this.free[i] = new OurOrder();
        }
        this.freeCount = orders;
    }

    /**
     * Binds a listing to its market: {@code complement} for the listing whose book and prices are the market's
     * complement. The first listing bound with programs supplies the market's; both listings of a market get the same
     * ones from the registry anyway.
     */
    public void bind(
            final int listingId,
            final String marketKey,
            final boolean complement,
            final LiquidityRule rule,
            final IncentiveTimeline timeline,
            final TickSize tick) {
        if (this.listings.containsKey(listingId)) {
            throw new IllegalArgumentException("Listing " + listingId + " is already bound");
        }
        final Market market = this.marketsByKey.computeIfAbsent(marketKey, key -> {
            final Market created = new Market(key, rule, tick, LEVELS + this.ordersPerMarket, this.ordersPerMarket);
            this.markets.add(created);
            return created;
        });
        final int slot = complement ? RewardAccrual.COMPLEMENT : RewardAccrual.PLAIN;
        if (market.listingIds[slot] != NONE) {
            throw new IllegalArgumentException("Market " + marketKey + " already has a "
                    + (complement ? "complement" : "plain") + " listing: " + market.listingIds[slot]);
        }
        market.listingIds[slot] = listingId;
        if (market.programs.length == 0 && timeline.size() > 0) {
            market.programs = new Program[timeline.size()];
            for (int i = 0; i < timeline.size(); i++) {
                market.programs[i] = new Program(market, rule, versions(timeline, i));
                this.accruals.add(market.programs[i].accrual);
            }
        }
        this.listings.put(listingId, new Binding(market, complement));
    }

    /**
     * Replaces the programs of a bound listing's market with those now known, as a session reloads them. A program it
     * already had keeps what it accrued and takes its new versions from now on; a new one starts accruing from now; one
     * whose period has already ended isn't added. Allocates, so call it off the hot path.
     */
    public void setPrograms(final int listingId, final IncentiveTimeline timeline) {
        final Binding binding = this.listings.get(listingId);
        if (binding == null) {
            throw new IllegalArgumentException("Listing " + listingId + " is not bound");
        }
        final Market market = binding.market;
        integrate(market, this.clock);
        final List<Program> programs = new ArrayList<>(List.of(market.programs));
        for (int i = 0; i < timeline.size(); i++) {
            final IncentiveProgram[] versions = versions(timeline, i);
            final String id = versions[versions.length - 1].venueProgramId();
            final Program existing = programs.stream()
                    .filter(p -> p.accrual.venueProgramId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                existing.update(market.rule, versions);
            } else if (versions[versions.length - 1].periodEndNanos() > this.clock) {
                final Program added = new Program(market, market.rule, versions);
                programs.add(added);
                this.accruals.add(added.accrual);
            }
        }
        market.programs = programs.toArray(Program[]::new);
    }

    /**
     * Drops every program whose period ended at or before {@code nanos}, once its final totals have been read, so a
     * long-running scorer holds only live programs. Returns how many were dropped. Allocates.
     */
    public int retireEndedBefore(final long nanos) {
        int retired = 0;
        for (int m = 0; m < this.markets.size(); m++) {
            final Market market = this.markets.get(m);
            final List<Program> kept = new ArrayList<>(market.programs.length);
            for (final Program program : market.programs) {
                if (program.periodEnd() <= nanos) {
                    this.accruals.remove(program.accrual);
                    retired++;
                } else {
                    kept.add(program);
                }
            }
            if (kept.size() != market.programs.length) {
                market.programs = kept.toArray(Program[]::new);
            }
        }
        return retired;
    }

    /** A program's versions in effective order, read back from the latest one. */
    private static IncentiveProgram[] versions(final IncentiveTimeline timeline, final int index) {
        final List<IncentiveProgram> versions = new ArrayList<>();
        IncentiveProgram version = timeline.versionAt(index, Long.MAX_VALUE);
        while (version != null) {
            versions.add(0, version);
            final long before = version.effectiveFromNanos() - 1;
            version = before < version.effectiveFromNanos() ? timeline.versionAt(index, before) : null;
        }
        return versions.toArray(IncentiveProgram[]::new);
    }

    /**
     * A listing's book, in its own terms, best level first; a level with no size is empty. Only the market's canonical
     * book is scored; the other listing's book only moves time on.
     */
    public void onBook(
            final int listingId,
            final long timestampNanos,
            final long[] bidPrice,
            final long[] bidSize,
            final long[] askPrice,
            final long[] askSize) {
        final Binding binding = this.listings.get(listingId);
        if (binding == null) {
            return;
        }
        final Market market = binding.market;
        final long now = tick(timestampNanos);
        integrate(market, now);
        if (binding.complement == market.complementIsCanonical()) {
            if (binding.complement) {
                market.bidCount = copy(askPrice, askSize, market.bidPrice, market.bidSize, true);
                market.askCount = copy(bidPrice, bidSize, market.askPrice, market.askSize, true);
            } else {
                market.bidCount = copy(bidPrice, bidSize, market.bidPrice, market.bidSize, false);
                market.askCount = copy(askPrice, askSize, market.askPrice, market.askSize, false);
            }
            market.hasBook = true;
            market.gap = false;
        }
    }

    /** {@link #onBook} from a recorded MBP-10 book, at its event time. */
    public void onBook(final int listingId, final Mbp10Decoder book) {
        onBook(listingId, book.timestampEvent(), book);
    }

    /** {@link #onBook} from an MBP-10 book, at {@code timestampNanos} (a session takes each book when it arrived). */
    public void onBook(final int listingId, final long timestampNanos, final Mbp10Decoder book) {
        final long nullPrice = Mbp10Decoder.priceNullValue();
        final long nullSize = Mbp10Decoder.sizeNullValue();
        level(0, book.bidPrice0(), book.bidSize0(), book.askPrice0(), book.askSize0(), nullPrice, nullSize);
        level(1, book.bidPrice1(), book.bidSize1(), book.askPrice1(), book.askSize1(), nullPrice, nullSize);
        level(2, book.bidPrice2(), book.bidSize2(), book.askPrice2(), book.askSize2(), nullPrice, nullSize);
        level(3, book.bidPrice3(), book.bidSize3(), book.askPrice3(), book.askSize3(), nullPrice, nullSize);
        level(4, book.bidPrice4(), book.bidSize4(), book.askPrice4(), book.askSize4(), nullPrice, nullSize);
        level(5, book.bidPrice5(), book.bidSize5(), book.askPrice5(), book.askSize5(), nullPrice, nullSize);
        level(6, book.bidPrice6(), book.bidSize6(), book.askPrice6(), book.askSize6(), nullPrice, nullSize);
        level(7, book.bidPrice7(), book.bidSize7(), book.askPrice7(), book.askSize7(), nullPrice, nullSize);
        level(8, book.bidPrice8(), book.bidSize8(), book.askPrice8(), book.askSize8(), nullPrice, nullSize);
        level(9, book.bidPrice9(), book.bidSize9(), book.askPrice9(), book.askSize9(), nullPrice, nullSize);
        onBook(listingId, timestampNanos, this.bidPrices, this.bidSizes, this.askPrices, this.askSizes);
    }

    private void level(
            final int index,
            final long bidPrice,
            final long bidSize,
            final long askPrice,
            final long askSize,
            final long nullPrice,
            final long nullSize) {
        final boolean bid = bidPrice != nullPrice && bidSize != nullSize;
        final boolean ask = askPrice != nullPrice && askSize != nullSize;
        this.bidPrices[index] = bid ? bidPrice : 0;
        this.bidSizes[index] = bid ? bidSize : 0;
        this.askPrices[index] = ask ? askPrice : 0;
        this.askSizes[index] = ask ? askSize : 0;
    }

    /** Copies a side's non-empty levels, complemented when the source is the complement listing's. */
    private static int copy(
            final long[] prices, final long[] sizes, final long[] toPrices, final long[] toSizes, final boolean flip) {
        int count = 0;
        for (int i = 0; i < LEVELS && i < prices.length; i++) {
            if (sizes[i] <= 0) {
                continue;
            }
            toPrices[count] = flip ? CERTAIN - prices[i] : prices[i];
            toSizes[count] = sizes[i];
            count++;
        }
        return count;
    }

    /**
     * One of our orders as it now rests, in its listing's terms; a resting size of 0 means it's gone. A buy on the
     * plain listing or a sell on the complement bids for the plain outcome; the others bid for the complement, which
     * sits on the plain listing's ask side at the complementary price.
     */
    public void onOrder(
            final int listingId,
            final long clientOid,
            final boolean buy,
            final long price,
            final long restingSize,
            final long timestampNanos) {
        final Binding binding = this.listings.get(listingId);
        if (binding == null) {
            return;
        }
        final Market market = binding.market;
        integrate(market, tick(timestampNanos));
        OurOrder order = this.orders.get(clientOid);
        if (restingSize <= 0) {
            if (order != null) {
                remove(order);
            }
            return;
        }
        if (order == null) {
            if (this.freeCount == 0 || market.orderCount == market.orders.length) {
                this.ordersDropped++;
                market.overflow = true;
                return;
            }
            order = this.free[--this.freeCount];
            order.clientOid = clientOid;
            order.market = market;
            market.orders[market.orderCount++] = order;
            this.orders.put(clientOid, order);
        }
        order.slot = binding.complement ? RewardAccrual.COMPLEMENT : RewardAccrual.PLAIN;
        order.plainBid = buy != binding.complement;
        order.bookPrice = binding.complement ? CERTAIN - price : price;
        order.size = restingSize / SIZE_SCALE;
    }

    private void remove(final OurOrder order) {
        final Market market = order.market;
        for (int i = 0; i < market.orderCount; i++) {
            if (market.orders[i] == order) {
                market.orders[i] = market.orders[--market.orderCount];
                market.orders[market.orderCount] = null;
                break;
            }
        }
        this.orders.remove(order.clientOid);
        order.market = null;
        this.free[this.freeCount++] = order;
    }

    /** The listing's market state was lost: it scores nothing until its next canonical book. */
    public void onGap(final int listingId, final long timestampNanos) {
        final Binding binding = this.listings.get(listingId);
        if (binding == null) {
            return;
        }
        integrate(binding.market, tick(timestampNanos));
        binding.market.gap = true;
    }

    /**
     * Starts every bound market's clock at {@code timestampNanos}: time before it isn't scored, not even as no data,
     * as before a backtest's start. Call after binding and before the first event.
     */
    public void beginAt(final long timestampNanos) {
        final long now = tick(timestampNanos);
        for (int i = 0; i < this.markets.size(); i++) {
            final Market market = this.markets.get(i);
            if (market.lastNanos < now) {
                market.lastNanos = now;
            }
        }
    }

    /** Integrates every market up to {@code timestampNanos} without a new event. */
    public void advanceTo(final long timestampNanos) {
        final long now = tick(timestampNanos);
        for (int i = 0; i < this.markets.size(); i++) {
            integrate(this.markets.get(i), now);
        }
    }

    public int accrualCount() {
        return this.accruals.size();
    }

    public RewardAccrual accrual(final int index) {
        return this.accruals.get(index);
    }

    /** Orders we couldn't track because a limit was reached; their markets scored nothing from then on. */
    public long ordersDropped() {
        return this.ordersDropped;
    }

    private long tick(final long timestampNanos) {
        if (timestampNanos > this.clock) {
            this.clock = timestampNanos;
        }
        return this.clock;
    }

    /** Accrues a market's constant state from its last event to {@code until}. */
    private void integrate(final Market market, final long until) {
        final long from = market.lastNanos;
        if (until <= from) {
            return;
        }
        market.lastNanos = until;
        if (market.programs.length == 0) {
            return;
        }
        final boolean scorable = market.hasBook && !market.gap && !market.overflow;
        if (scorable) {
            market.buildSides(this.mode);
        }
        for (final Program program : market.programs) {
            integrate(market, program, from, until, scorable);
        }
    }

    /** Accrues one program over [from, until), split where its versions change and clipped to their periods. */
    private static void integrate(
            final Market market, final Program program, final long from, final long until, final boolean scorable) {
        final IncentiveProgram[] versions = program.versions;
        for (int v = 0; v < versions.length; v++) {
            final IncentiveProgram version = versions[v];
            final long nextVersion = v + 1 < versions.length ? versions[v + 1].effectiveFromNanos() : Long.MAX_VALUE;
            final long start = Math.max(Math.max(from, version.effectiveFromNanos()), version.periodStartNanos());
            final long end = Math.min(Math.min(until, nextVersion), version.periodEndNanos());
            if (end <= start) {
                continue;
            }
            final double nanos = (double) (end - start);
            if (scorable) {
                market.score(program, v, nanos, start);
            } else {
                program.accrual.noDataNanos += nanos;
            }
        }
    }

    private static final class Binding {
        final Market market;
        final boolean complement;

        Binding(final Market market, final boolean complement) {
            this.market = market;
            this.complement = complement;
        }
    }

    private static final class OurOrder {
        long clientOid;
        Market market;
        int slot;
        boolean plainBid;
        long bookPrice;
        double size;
        int level;
    }

    private static final class Program {
        IncentiveProgram[] versions;
        Object[] terms;
        double[] rewardPerShareNano;
        final RewardAccrual accrual;

        Program(final Market market, final LiquidityRule rule, final IncentiveProgram[] versions) {
            final IncentiveProgram latest = versions[versions.length - 1];
            this.accrual = new RewardAccrual(market.key, latest.venueProgramId(), rule, market.listingIds, latest);
            update(rule, versions);
        }

        /** The program's versions as now known; what it accrued so far stays. */
        void update(final LiquidityRule rule, final IncentiveProgram[] newVersions) {
            this.versions = newVersions;
            this.terms = new Object[newVersions.length];
            this.rewardPerShareNano = new double[newVersions.length];
            for (int i = 0; i < newVersions.length; i++) {
                this.terms[i] = rule.terms(newVersions[i]);
                this.rewardPerShareNano[i] = rule.rewardPerShareNano(newVersions[i]);
            }
            this.accrual.latest(newVersions[newVersions.length - 1]);
        }

        long periodEnd() {
            return this.versions[this.versions.length - 1].periodEndNanos();
        }
    }

    private static final class Market {
        private static final Program[] NO_PROGRAMS = new Program[0];

        final String key;
        final LiquidityRule rule;
        final TickSize tick;
        final int[] listingIds = {NONE, NONE};
        Program[] programs = NO_PROGRAMS;
        final long[] bidPrice = new long[LEVELS];
        final long[] bidSize = new long[LEVELS];
        final long[] askPrice = new long[LEVELS];
        final long[] askSize = new long[LEVELS];
        int bidCount;
        int askCount;
        boolean hasBook;
        boolean gap;
        boolean overflow;
        long lastNanos = Long.MIN_VALUE;
        final OurOrder[] orders;
        int orderCount;
        final SideLevels bids;
        final SideLevels asks;
        final SideScore bidScore;
        final SideScore askScore;

        Market(final String key, final LiquidityRule rule, final TickSize tick, final int levels, final int orders) {
            this.key = key;
            this.rule = rule;
            this.tick = tick;
            this.orders = new OurOrder[orders];
            this.bids = new SideLevels(levels, true);
            this.asks = new SideLevels(levels, false);
            this.bidScore = new SideScore(levels);
            this.askScore = new SideScore(levels);
        }

        /** The complement listing's book is canonical only when the plain listing isn't bound. */
        boolean complementIsCanonical() {
            return this.listingIds[RewardAccrual.PLAIN] == NONE;
        }

        /** Both sides of the market in its plain terms, with our orders. */
        void buildSides(final Mode mode) {
            this.bids.clear();
            this.asks.clear();
            for (int i = 0; i < this.bidCount; i++) {
                this.bids.add(this.bidPrice[i], this.bidSize[i] / SIZE_SCALE, 0);
            }
            for (int i = 0; i < this.askCount; i++) {
                this.asks.add(this.askPrice[i], this.askSize[i] / SIZE_SCALE, 0);
            }
            this.bids.depthFull = this.bidCount == LEVELS;
            this.asks.depthFull = this.askCount == LEVELS;
            for (int i = 0; i < this.orderCount; i++) {
                final OurOrder order = this.orders[i];
                (order.plainBid ? this.bids : this.asks).add(order.bookPrice, 0, order.size);
            }
            if (mode == Mode.OURS_IN_BOOK) {
                withoutOurs(this.bids);
                withoutOurs(this.asks);
            }
            for (int i = 0; i < this.orderCount; i++) {
                final OurOrder order = this.orders[i];
                order.level = (order.plainBid ? this.bids : this.asks).levelOf(order.bookPrice);
            }
        }

        private static void withoutOurs(final SideLevels side) {
            for (int i = 0; i < side.count; i++) {
                side.others[i] = Math.max(0, side.others[i] - side.ours[i]);
            }
        }

        /** Scores a stretch of {@code nanos} under one version of a program, starting at {@code start}. */
        void score(final Program program, final int version, final double nanos, final long start) {
            final RewardAccrual accrual = program.accrual;
            final Object terms = program.terms[version];
            final long tickSize = this.tick.at(start);
            this.rule.scoreSide(this.bids, terms, tickSize, this.bidScore);
            this.rule.scoreSide(this.asks, terms, tickSize, this.askScore);
            if (!this.bidScore.qualifies || !this.askScore.qualifies) {
                if (this.bidScore.depthUnknown || this.askScore.depthUnknown) {
                    accrual.depthUnknownNanos += nanos;
                } else {
                    accrual.oneSidedNanos += nanos;
                }
                return;
            }
            accrual.countingNanos += nanos;
            accrueOurs(accrual, program.rewardPerShareNano[version], nanos);
        }

        /** Gives each of our orders its part of its side's score over the stretch. */
        private void accrueOurs(final RewardAccrual accrual, final double rate, final double nanos) {
            for (int i = 0; i < this.orderCount; i++) {
                final OurOrder order = this.orders[i];
                final SideLevels side = order.plainBid ? this.bids : this.asks;
                final SideScore score = order.plainBid ? this.bidScore : this.askScore;
                final double oursAtLevel = side.ours[order.level];
                if (oursAtLevel <= 0 || score.total <= 0) {
                    continue;
                }
                final double share = score.ours[order.level] * (order.size / oursAtLevel) / score.total;
                accrual.add(order.slot, share * nanos, share * nanos * rate);
            }
        }
    }
}
