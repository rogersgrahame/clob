package org.greeps.clob.core;

/**
 * Pre-allocated pool of reusable Order objects.
 *
 * All access is from a single engine thread — no synchronisation required.
 * If the pool is exhausted (book depth exceeds capacity), borrow() falls back
 * to heap allocation so correctness is preserved. Size the pool to the expected
 * maximum book depth to keep the steady state allocation-free.
 */
public final class OrderPool {

    private final Order[] pool;
    private int top;

    public OrderPool(int capacity) {
        pool = new Order[capacity];
        for (int i = 0; i < capacity; i++) {
            pool[i] = new Order();
        }
        top = capacity;
    }

    /**
     * Borrow an Order from the pool and initialise it.
     * Falls back to heap allocation if the pool is exhausted.
     */
    public Order borrow(long orderId, String instrumentId, Side side, OrderType type,
                        long price, long quantity, long timestamp) {
        Order order = top > 0 ? pool[--top] : new Order();
        order.reset(orderId, instrumentId, side, type, price, quantity, timestamp);
        return order;
    }

    /**
     * Return an Order to the pool once it has left the book.
     * Safe to ignore if the pool is full (should not happen if sized correctly).
     */
    public void release(Order order) {
        if (top < pool.length) {
            pool[top++] = order;
        }
    }

    public int available() { return top; }
    public int capacity()  { return pool.length; }
}
