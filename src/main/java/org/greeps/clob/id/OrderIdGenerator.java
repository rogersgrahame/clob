package org.greeps.clob.id;

import java.util.concurrent.atomic.AtomicLong;

public final class OrderIdGenerator {

    private final AtomicLong sequence = new AtomicLong(0);

    public long next() {
        return sequence.incrementAndGet();
    }
}
