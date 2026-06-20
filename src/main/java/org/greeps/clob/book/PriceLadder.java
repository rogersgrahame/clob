package org.greeps.clob.book;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * Sorted price-level structure with zero boxing.
 *
 * Maintains a sorted long[] of active prices (best price at index 0) plus
 * a LongObjectHashMap for O(1) level access by price. Replaces TreeMap<Long,PriceLevel>:
 * no Long boxing on any price-level operation (get, insert, remove, bestPrice).
 *
 * Insertion/removal are O(n) due to array shifts, but for typical book depths
 * (< 100 active levels) this is faster and more cache-friendly than a tree.
 */
public final class PriceLadder {

    public static final int DEFAULT_MAX_LEVELS = 1024;

    private final long[] prices;
    private final LongObjectHashMap<PriceLevel> levels;
    private int size;
    private final boolean descending;

    public PriceLadder(boolean descending) {
        this(DEFAULT_MAX_LEVELS, descending);
    }

    public PriceLadder(int maxLevels, boolean descending) {
        this.prices     = new long[maxLevels];
        this.levels     = new LongObjectHashMap<>(maxLevels);
        this.descending = descending;
    }

    /** Return the existing level for this price, or create and insert a new one. */
    public PriceLevel getOrCreate(long price) {
        PriceLevel level = levels.get(price);
        if (level == null) {
            level = new PriceLevel();
            levels.put(price, level);
            insertSorted(price);
        }
        return level;
    }

    /** Return the level for this price, or null if none exists. */
    public PriceLevel get(long price) {
        return levels.get(price);
    }

    /** Remove the level at this price entirely (called when a level drains to empty). */
    public void remove(long price) {
        levels.removeKey(price);
        removeSorted(price);
    }

    /** Best (highest for bids, lowest for asks) active price, or Long.MIN_VALUE if empty. */
    public long bestPrice() {
        return size > 0 ? prices[0] : Long.MIN_VALUE;
    }

    /** PriceLevel at the best price, or null if empty. */
    public PriceLevel bestLevel() {
        return size > 0 ? levels.get(prices[0]) : null;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    // --- sorted array maintenance ---

    private void insertSorted(long price) {
        if (size >= prices.length) {
            throw new IllegalStateException(
                "PriceLadder capacity exhausted (" + prices.length + "). Increase maxLevels.");
        }
        int pos = insertionPoint(price);
        if (size > pos) {
            System.arraycopy(prices, pos, prices, pos + 1, size - pos);
        }
        prices[pos] = price;
        size++;
    }

    private void removeSorted(long price) {
        int idx = findIndex(price);
        if (idx < 0) return;
        int tail = size - idx - 1;
        if (tail > 0) {
            System.arraycopy(prices, idx + 1, prices, idx, tail);
        }
        size--;
    }

    /** Binary search: first position where the existing element compares >= price. */
    private int insertionPoint(long price) {
        int lo = 0, hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(prices[mid], price) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Binary search: exact position of price, or -1 if absent. */
    private int findIndex(long price) {
        int lo = 0, hi = size - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compare(prices[mid], price);
            if (cmp < 0) lo = mid + 1;
            else if (cmp > 0) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    private int compare(long a, long b) {
        return descending ? Long.compare(b, a) : Long.compare(a, b);
    }
}
