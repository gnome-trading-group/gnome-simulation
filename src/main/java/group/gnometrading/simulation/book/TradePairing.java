package group.gnometrading.simulation.book;

import java.time.Duration;

/**
 * Whether, and how far apart in venue time, a book change and a trade record are read as one trade.
 *
 * <p>A venue's recorded feed can deliver a trade's effect on the book (its level shrinking by the traded size) before
 * the trade record itself. Read separately, the book change looks like cancels and the trade then takes the same
 * volume off the queue a second time, which moves our resting orders up a trade too far. With pairing, a trade that
 * arrives after a shrink of its level claims the shrink, and the queue is rebuilt as if the trade had come first.
 *
 * <p>The bounds describe the venue's feed, not a modelling choice: how much earlier ({@code earlierNanos}) or later
 * ({@code laterNanos}) than the trade's own stamp its book change can be stamped while still arriving first. A cancel
 * just before an unrelated trade inside the bounds is paired by mistake, which can only underfill us, so a venue whose
 * feed sends trades first should stay {@link #OFF}.
 */
public record TradePairing(boolean enabled, long earlierNanos, long laterNanos) {

    /** Book changes and trades are read separately. */
    public static final TradePairing OFF = new TradePairing(false, 0, 0);

    /** Only a book change stamped at the trade's own time pairs. */
    public static final TradePairing SAME_TIMESTAMP = new TradePairing(true, 0, 0);

    public TradePairing {
        if (earlierNanos < 0 || laterNanos < 0) {
            throw new IllegalArgumentException("Pairing bounds must not be negative");
        }
    }

    /** A book change stamped from {@code earlier} before to {@code later} after the trade pairs with it. */
    public static TradePairing within(Duration earlier, Duration later) {
        return new TradePairing(true, earlier.toNanos(), later.toNanos());
    }

    /** Whether a trade at {@code tradeNanos} can claim shrinks stamped from {@code firstNanos} to {@code lastNanos}. */
    public boolean pairs(long firstNanos, long lastNanos, long tradeNanos) {
        return enabled && lastNanos >= tradeNanos - earlierNanos && firstNanos <= tradeNanos + laterNanos;
    }

    /**
     * Whether a shrink at {@code shrinkNanos} joins one pending since {@code firstNanos}: a single trade could pair with
     * both only if they are no further apart than the bounds span.
     */
    public boolean continues(long firstNanos, long shrinkNanos) {
        return Math.abs(shrinkNanos - firstNanos) <= earlierNanos + laterNanos;
    }
}
