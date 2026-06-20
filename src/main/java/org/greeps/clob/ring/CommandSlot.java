package org.greeps.clob.ring;

import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;

/**
 * Pre-allocated, mutable command slot used by CommandRingBuffer.
 *
 * {@code sequence} and {@code claimedSeq} are managed exclusively by CommandRingBuffer.
 */
public final class CommandSlot {

    /** Coordination field — managed by CommandRingBuffer. */
    public volatile long sequence;
    /** Sequence claimed by this producer; read back during publish. */
    long claimedSeq;

    public CommandType commandType;
    public String      instrumentId;
    public Side        side;
    public OrderType   orderType;
    public long        price;
    public long        quantity;
    public long        orderId;
    public long        newPrice;
    public long        newQuantity;

    CommandSlot(long initialSequence) {
        this.sequence = initialSequence;
    }
}
