package group.gnometrading.simulation.book;

import java.util.List;

/**
 * How an incoming order would execute against the book, before anything is applied.
 *
 * @param matches market liquidity it takes, one entry per price level
 * @param restingToCancel our own resting orders the self-trade rule cancels so matching can continue
 * @param stoppedAtSelf true when matching stopped at our own resting order and the remainder must be cancelled
 */
public record MatchPlan(List<OrderMatch> matches, List<LocalOrder> restingToCancel, boolean stoppedAtSelf) {}
