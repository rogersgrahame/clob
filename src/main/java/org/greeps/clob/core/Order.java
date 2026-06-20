package org.greeps.clob.core;

public final class Order {

    private final long orderId;
    private final String instrumentId;
    private final Side side;
    private final OrderType type;
    private final long price;      // ticks; 0 for MARKET orders
    private final long quantity;   // original, immutable
    private final long timestamp;  // System.nanoTime() at submission
    private long remaining;
    private OrderStatus status;

    public Order(long orderId, String instrumentId, Side side, OrderType type,
                 long price, long quantity, long timestamp) {
        this.orderId = orderId;
        this.instrumentId = instrumentId;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.remaining = quantity;
        this.status = OrderStatus.NEW;
        this.timestamp = timestamp;
    }

    public void fill(long qty) {
        remaining -= qty;
        status = remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIAL;
    }

    public void cancel() {
        status = OrderStatus.CANCELLED;
    }

    public long orderId()       { return orderId; }
    public String instrumentId(){ return instrumentId; }
    public Side side()          { return side; }
    public OrderType type()     { return type; }
    public long price()         { return price; }
    public long quantity()      { return quantity; }
    public long remaining()     { return remaining; }
    public long timestamp()     { return timestamp; }
    public OrderStatus status() { return status; }
}
