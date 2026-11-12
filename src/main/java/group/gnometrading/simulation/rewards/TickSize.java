package group.gnometrading.simulation.rewards;

/** A listing's price tick at a time, in price units (1e9 = $1), from its point-in-time listing spec. */
@FunctionalInterface
public interface TickSize {
    long at(long nanos);
}
