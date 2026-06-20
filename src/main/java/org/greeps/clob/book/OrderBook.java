package org.greeps.clob.book;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.greeps.clob.core.Order;
import org.greeps.clob.core.Side;

/**
 * Per-instrument order book.
 *
 * Bids and asks are managed by PriceLadder — a sorted long[] of active prices
 * paired with a LongObjectHashMap for O(1) level access. No Long boxing on any
 * price-level operation.
 */
public final class OrderBook {

    /** Sentinel returned by bestOppositePrice() when the opposite side is empty. */
    public static final long NO_PRICE = Long.MIN_VALUE;

    private final PriceLadder              bids       = new PriceLadder(true);   // descending: highest first
    private final PriceLadder              asks       = new PriceLadder(false);  // ascending:  lowest first
    private final LongObjectHashMap<Order> orderIndex = new LongObjectHashMap<>();

    public void add(Order order) {
        orderIndex.put(order.orderId(), order);
        ladderFor(order.side()).getOrCreate(order.price()).add(order);
    }

    public Order find(long orderId) {
        return orderIndex.get(orderId);
    }

    /** Remove an order by ID (explicit cancel). Returns the order, or null if not found. */
    public Order remove(long orderId) {
        Order order = orderIndex.removeKey(orderId);
        if (order == null) return null;
        removeFromLevel(order);
        return order;
    }

    /** Remove the front order from the best resting level after a full fill during matching. */
    public void removeTopOfBook(Side restingSide) {
        PriceLadder ladder    = ladderFor(restingSide);
        if (ladder.isEmpty()) return;
        long       bestPrice  = ladder.bestPrice();
        PriceLevel level      = ladder.bestLevel();
        Order      removed    = level.poll();
        if (removed != null) orderIndex.removeKey(removed.orderId());
        if (level.isEmpty()) ladder.remove(bestPrice);
    }

    /** Peek at the front order on the best opposite level without removing it. */
    public Order peekBestOpposite(Side aggressiveSide) {
        PriceLevel level = ladderFor(opposite(aggressiveSide)).bestLevel();
        return level != null ? level.peek() : null;
    }

    /**
     * Best resting price on the opposite side as a primitive long.
     * Returns NO_PRICE if the opposite side is empty.
     */
    public long bestOppositePrice(Side aggressiveSide) {
        return ladderFor(opposite(aggressiveSide)).bestPrice();
    }

    public boolean isEmpty(Side side) {
        return ladderFor(side).isEmpty();
    }

    // --- private helpers ---

    private void removeFromLevel(Order order) {
        PriceLadder ladder = ladderFor(order.side());
        PriceLevel  level  = ladder.get(order.price());
        if (level == null) return;
        level.remove(order);
        if (level.isEmpty()) ladder.remove(order.price());
    }

    private PriceLadder ladderFor(Side side) {
        return side == Side.BID ? bids : asks;
    }

    private Side opposite(Side side) {
        return side == Side.BID ? Side.ASK : Side.BID;
    }
}
