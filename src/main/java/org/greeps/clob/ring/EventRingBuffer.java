package org.greeps.clob.ring;

/**
 * Single-Producer Single-Consumer ring buffer for events.
 *
 * Coordination uses per-slot volatile {@code sequence} fields — no locks, no
 * AtomicLong CAS in the hot path. Zero allocation after construction.
 *
 * Sequence protocol (capacity = N):
 *   - Slot[i] initialised with sequence = i.
 *   - Producer claims slot at producerSeq: spins until slot.sequence == producerSeq.
 *   - Producer publishes: slot.sequence = producerSeq + 1  (volatile write).
 *   - Consumer reads slot at consumerSeq: ready when slot.sequence == consumerSeq + 1.
 *   - Consumer releases: slot.sequence = consumerSeq + N  (volatile write, enables next generation).
 */
public final class EventRingBuffer {

    private final MutableEvent[] buffer;
    private final int mask;

    private long producerSeq = 0;  // written only by producer thread
    private long consumerSeq = 0;  // written only by consumer thread

    public EventRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("capacity must be a power of 2");
        buffer = new MutableEvent[capacity];
        mask   = capacity - 1;
        for (int i = 0; i < capacity; i++) {
            buffer[i] = new MutableEvent(i);
        }
    }

    // --- producer side ---

    /**
     * Claim the next writable slot. Spins if the consumer has not yet freed it.
     * Called exclusively from the producer thread.
     */
    public MutableEvent claim() {
        MutableEvent slot = buffer[(int) (producerSeq & mask)];
        while (slot.sequence != producerSeq) Thread.onSpinWait();
        return slot;
    }

    /**
     * Publish a slot after all fields have been written.
     * The volatile write to {@code sequence} is the happens-before boundary.
     */
    public void publish(MutableEvent slot) {
        slot.sequence = producerSeq + 1;  // volatile write — visible to consumer
        producerSeq++;
    }

    // --- consumer side ---

    /**
     * Returns the next available slot, or {@code null} if none is ready.
     * Called exclusively from the consumer thread.
     */
    public MutableEvent poll() {
        MutableEvent slot = buffer[(int) (consumerSeq & mask)];
        if (slot.sequence != consumerSeq + 1) return null;  // volatile read
        return slot;
    }

    /**
     * Release a slot after it has been fully processed.
     * The volatile write re-enables the slot for the producer's next generation.
     */
    public void done(MutableEvent slot) {
        slot.sequence = consumerSeq + buffer.length;  // volatile write — visible to producer
        consumerSeq++;
    }

    /** Drain all available events, releasing each slot. Used in tests and benchmarks. */
    public void drainAll() {
        MutableEvent slot;
        while ((slot = poll()) != null) done(slot);
    }
}
