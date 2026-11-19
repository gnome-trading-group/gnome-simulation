package group.gnometrading.simulation.rewards;

import group.gnometrading.sm.PriceGrid;

/** A listing's price grid at a time, from its point-in-time listing spec. */
@FunctionalInterface
public interface PriceGrids {
    PriceGrid at(long nanos);
}
