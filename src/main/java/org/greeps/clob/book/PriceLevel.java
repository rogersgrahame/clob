package org.greeps.clob.book;

import org.greeps.clob.core.Order;

import java.util.ArrayDeque;

/**
 * Orders resting at a single price, maintained in time (FIFO) order.
 * Phase 1 swap point: replace ArrayDeque for lower-allocation alternatives in Phase 2.
 */
public final class PriceLevel {

    private final ArrayDeque<Order> orders = new ArrayDeque<>();

    public void add(Order order) {
        orders.addLast(order);
    }

    /** Peek at the oldest (highest-priority) resting order without removing it. */
    public Order peek() {
        return orders.peekFirst();
    }

    /** Remove and return the oldest resting order. Called when it is fully filled during matching. */
    public Order poll() {
        return orders.pollFirst();
    }

    /** Remove an arbitrary order. Called on explicit cancel; O(n) within the level. */
    public boolean remove(Order order) {
        return orders.remove(order);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public int size() {
        return orders.size();
    }
}
