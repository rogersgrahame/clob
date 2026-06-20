package org.greeps.clob.book;

import org.greeps.clob.core.Order;
import org.greeps.clob.core.Side;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-instrument order book.
 *
 * Phase 1 swap points:
 *   - TreeMap  → custom price-indexed array or skip list
 *   - ArrayDeque (inside PriceLevel) → object-pool or off-heap structure
 */
public final class OrderBook {

    // Bids: highest price first
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    // Asks: lowest price first (natural order)
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    // O(1) lookup by orderId for cancel / modify
    private final HashMap<Long, Order> orderIndex = new HashMap<>();

    public void add(Order order) {
        orderIndex.put(order.orderId(), order);
        sideMap(order.side())
                .computeIfAbsent(order.price(), p -> new PriceLevel())
                .add(order);
    }

    public Order find(long orderId) {
        return orderIndex.get(orderId);
    }

    /**
     * Remove an order by ID (used for explicit cancel).
     * Returns the removed order, or null if not found.
     */
    public Order remove(long orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) return null;
        removefromLevel(order);
        return order;
    }

    /**
     * Remove the front order from the best opposite level after a full fill during matching.
     * Cleans up the level from the TreeMap if it becomes empty.
     */
    public void removeTopOfBook(Side restingSide) {
        TreeMap<Long, PriceLevel> map = sideMap(restingSide);
        Map.Entry<Long, PriceLevel> best = map.firstEntry();
        if (best == null) return;
        Order removed = best.getValue().poll();
        if (removed != null) orderIndex.remove(removed.orderId());
        if (best.getValue().isEmpty()) map.remove(best.getKey());
    }

    /** Peek at the best resting order on the opposite side without removing it. */
    public Order peekBestOpposite(Side aggressiveSide) {
        Map.Entry<Long, PriceLevel> best = sideMap(opposite(aggressiveSide)).firstEntry();
        return best != null ? best.getValue().peek() : null;
    }

    /** Best resting price on the opposite side, or null if the book is empty. */
    public Long bestOppositePrice(Side aggressiveSide) {
        Map.Entry<Long, PriceLevel> best = sideMap(opposite(aggressiveSide)).firstEntry();
        return best != null ? best.getKey() : null;
    }

    public boolean isEmpty(Side side) {
        return sideMap(side).isEmpty();
    }

    // --- private helpers ---

    private void removefromLevel(Order order) {
        TreeMap<Long, PriceLevel> map = sideMap(order.side());
        PriceLevel level = map.get(order.price());
        if (level == null) return;
        level.remove(order);
        if (level.isEmpty()) map.remove(order.price());
    }

    private TreeMap<Long, PriceLevel> sideMap(Side side) {
        return side == Side.BID ? bids : asks;
    }

    private Side opposite(Side side) {
        return side == Side.BID ? Side.ASK : Side.BID;
    }
}
