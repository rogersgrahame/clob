package org.greeps.clob.ring;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-Producer Single-Consumer ring buffer for commands.
 *
 * Multiple producer threads claim slots via an AtomicLong CAS-free increment.
 * Each slot's volatile {@code sequence} field coordinates producer/consumer visibility.
 * Zero allocation after construction in the steady state.
 *
 * Sequence protocol (capacity = N):
 *   - Slot[i] initialised with sequence = i.
 *   - Producer claims: atomically increments producerCursor, spins on slot.sequence == seq.
 *   - Producer publishes: slot.sequence = seq + 1  (volatile write).
 *   - Consumer reads slot at consumerSeq: ready when slot.sequence == consumerSeq + 1.
 *   - Consumer releases: slot.sequence = consumerSeq + N  (volatile write).
 */
public final class CommandRingBuffer {

    private final CommandSlot[] buffer;
    private final int mask;
    private final AtomicLong producerCursor = new AtomicLong(0);

    private long consumerSeq = 0;  // written only by consumer thread

    public CommandRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("capacity must be a power of 2");
        buffer = new CommandSlot[capacity];
        mask   = capacity - 1;
        for (int i = 0; i < capacity; i++) {
            buffer[i] = new CommandSlot(i);
        }
    }

    // --- producer side ---

    /**
     * Claim the next writable slot. Spins if the consumer has not freed this slot
     * from the previous generation (backpressure). Safe to call from multiple threads.
     */
    public CommandSlot claim() {
        long seq  = producerCursor.getAndIncrement();
        CommandSlot slot = buffer[(int) (seq & mask)];
        while (slot.sequence != seq) Thread.onSpinWait();
        slot.claimedSeq = seq;
        return slot;
    }

    /**
     * Publish a slot after all fields have been written.
     * The volatile write to {@code sequence} is the happens-before boundary.
     */
    public void publish(CommandSlot slot) {
        slot.sequence = slot.claimedSeq + 1;  // volatile write — visible to consumer
    }

    // --- consumer side ---

    /**
     * Returns the next available slot in sequence, or {@code null} if none is ready.
     * Called exclusively from the consumer (engine) thread.
     */
    public CommandSlot poll() {
        CommandSlot slot = buffer[(int) (consumerSeq & mask)];
        if (slot.sequence != consumerSeq + 1) return null;  // volatile read
        return slot;
    }

    /**
     * Release a slot after it has been dispatched.
     * The volatile write re-enables the slot for producers' next generation.
     */
    public void done(CommandSlot slot) {
        slot.sequence = consumerSeq + buffer.length;  // volatile write
        consumerSeq++;
    }
}
