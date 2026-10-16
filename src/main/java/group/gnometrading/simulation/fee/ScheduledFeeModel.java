package group.gnometrading.simulation.fee;

import group.gnometrading.sm.fee.FeeSchedule;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.sm.fee.OrderFeeState;
import java.time.Instant;

/** Fees from the listing's schedules in the registry, as they stood at each fill. */
public final class ScheduledFeeModel implements FeeModel {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int listingId;
    private final FeeTimeline timeline;

    public ScheduledFeeModel(int listingId, FeeTimeline timeline) {
        this.listingId = listingId;
        this.timeline = timeline;
    }

    @Override
    public long fee(long nowNanos, boolean buy, long price, long quantity, boolean maker, OrderFeeState state) {
        FeeSchedule schedule = timeline.at(nowNanos);
        if (schedule == null) {
            // A fill priced without its fees would overstate the result, so this stops the run rather than guess.
            throw new IllegalStateException("Listing " + listingId + " has no fee schedule in the registry at "
                    + Instant.ofEpochSecond(nowNanos / NANOS_PER_SECOND, nowNanos % NANOS_PER_SECOND)
                    + "; record one, or set the profile's fee.model to static or parametric");
        }
        return schedule.fee(buy, price, quantity, maker, state);
    }
}
