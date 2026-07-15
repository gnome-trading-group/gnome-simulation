package group.gnometrading.simulation.book;

public record LocalOrderFill(LocalOrder localOrder, long fillSize, long remainingAfterFill) {}
