package org.greeps.clob.event;

public record OrderFilledEvent(
        long orderId,
        String instrumentId,
        long fillQty,
        long remainingQty,
        long fillPrice,
        long timestamp
) implements Event {

    public boolean isFull() {
        return remainingQty == 0;
    }
}
