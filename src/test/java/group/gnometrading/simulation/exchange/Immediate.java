package group.gnometrading.simulation.exchange;

import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import java.util.ArrayList;
import java.util.List;

/** Hands a message to the exchange and processes everything it holds, for tests that don't exercise latency. */
final class Immediate {

    private Immediate() {}

    static List<OrderExecutionReport> submit(SimulatedExchange exchange, Order order) {
        return drain(exchange, exchange.submitOrder(order, 0));
    }

    static List<OrderExecutionReport> cancel(SimulatedExchange exchange, CancelOrder cancel) {
        return drain(exchange, exchange.cancelOrder(cancel, 0));
    }

    static List<OrderExecutionReport> modify(SimulatedExchange exchange, ModifyOrder modify) {
        return drain(exchange, exchange.modifyOrder(modify, 0));
    }

    private static List<OrderExecutionReport> drain(SimulatedExchange exchange, List<OrderExecutionReport> immediate) {
        List<OrderExecutionReport> reports = new ArrayList<>(immediate);
        reports.addAll(exchange.processDue(Long.MAX_VALUE));
        return reports;
    }
}
