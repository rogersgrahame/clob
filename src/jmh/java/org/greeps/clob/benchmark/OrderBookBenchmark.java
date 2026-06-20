package org.greeps.clob.benchmark;

import org.greeps.clob.command.CancelOrderCommand;
import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.engine.MatchingEngine;
import org.greeps.clob.id.OrderIdGenerator;
import org.greeps.clob.ring.EventRingBuffer;
import org.greeps.clob.ring.EventType;
import org.greeps.clob.ring.MutableEvent;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmarks with TreeMap / ArrayDeque / OrderPool / ring buffers (Phase 1-3).
 * Store results — future collection swaps are compared against these numbers.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class OrderBookBenchmark {

    private MatchingEngine engine;
    private EventRingBuffer eventRing;

    @Setup(Level.Trial)
    public void setup() {
        eventRing = new EventRingBuffer(8192);
        engine    = new MatchingEngine("BENCH", new OrderIdGenerator(), eventRing);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        eventRing.drainAll();
    }

    // -----------------------------------------------------------------------
    // Throughput: every ASK+BID pair at the same price produces a full fill
    // -----------------------------------------------------------------------

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput_matchingPairs() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.ASK, OrderType.LIMIT, 100, 1));
        eventRing.drainAll();
        engine.processSync(new SubmitOrderCommand("BENCH", Side.BID, OrderType.LIMIT, 100, 1));
        eventRing.drainAll();
    }

    // -----------------------------------------------------------------------
    // Latency: single aggressive order against a pre-seeded resting order
    // -----------------------------------------------------------------------

    @Setup(Level.Invocation)
    public void seedRestingOrder() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.ASK, OrderType.LIMIT, 100, 1));
        eventRing.drainAll();
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void latency_singleMatch() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.BID, OrderType.LIMIT, 100, 1));
        eventRing.drainAll();
    }

    // -----------------------------------------------------------------------
    // Cancel-heavy: 50% submit, 50% cancel
    // -----------------------------------------------------------------------

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void cancelHeavy_submitThenCancel() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.ASK, OrderType.LIMIT, 101, 1));

        // Retrieve the accepted orderId from the ring buffer
        MutableEvent accepted = eventRing.poll();
        long orderId = 0;
        if (accepted != null && accepted.eventType == EventType.ORDER_ACCEPTED) {
            orderId = accepted.orderId;
            eventRing.done(accepted);
        }

        if (orderId != 0) {
            engine.processSync(new CancelOrderCommand(orderId, "BENCH"));
            eventRing.drainAll();
        }
    }
}
