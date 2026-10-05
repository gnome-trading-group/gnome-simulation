package group.gnometrading.simulation.exchange;

import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import java.util.List;

/**
 * A simulated venue. Orders, cancels and modifies are held for their processing time after they arrive, as at a real
 * venue, and only then touch the book. The caller drives time: it hands over each message as it arrives, and calls
 * {@link #processDue} when {@link #nextDueNanos} comes.
 */
public interface SimulatedExchange {

    /** An order arriving at {@code nowNanos}. Returns any immediate reject; everything else comes from processDue. */
    List<OrderExecutionReport> submitOrder(Order order, long nowNanos);

    List<OrderExecutionReport> cancelOrder(CancelOrder cancel, long nowNanos);

    List<OrderExecutionReport> modifyOrder(ModifyOrder modify, long nowNanos);

    /** Processes every held message due at or before {@code nowNanos}, in due-time then arrival order. */
    List<OrderExecutionReport> processDue(long nowNanos);

    /** When the next held message comes due, or {@link Long#MAX_VALUE} if none is held. */
    long nextDueNanos();

    List<OrderExecutionReport> onMarketData(Schema data);

    long simulateNetworkLatency();

    List<SchemaType> getSupportedSchemas();
}
