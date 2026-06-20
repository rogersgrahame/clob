package org.greeps.clob.benchmark;

import org.greeps.clob.command.CancelOrderCommand;
import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.engine.MatchingEngine;
import org.greeps.clob.event.Event;
import org.greeps.clob.event.OrderAcceptedEvent;
import org.greeps.clob.id.OrderIdGenerator;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmarks with TreeMap / ArrayDeque (Phase 1 collections).
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
    private LinkedTransferQueue<Event> eventQueue;
    private long bidPrice;
    private long askPrice;

    // for cancel-heavy benchmark
    private final Deque<Long> restingOrderIds = new ArrayDeque<>();

    @Setup(Level.Trial)
    public void setup() {
        eventQueue = new LinkedTransferQueue<>();
        engine = new MatchingEngine("BENCH", new OrderIdGenerator(), eventQueue);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        eventQueue.clear();
    }

    // -----------------------------------------------------------------------
    // Throughput: mixed limit + market, balanced buy/sell with no resting depth
    // -----------------------------------------------------------------------

    /**
     * Alternating ASK then BID at the same price — every pair produces a full fill.
     * Measures raw matching throughput (orders/sec) with zero resting book state.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput_matchingPairs() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.ASK, OrderType.LIMIT, 100, 1));
        eventQueue.clear();
        engine.processSync(new SubmitOrderCommand("BENCH", Side.BID, OrderType.LIMIT, 100, 1));
        eventQueue.clear();
    }

    // -----------------------------------------------------------------------
    // Latency: single aggressive order against a pre-seeded resting order
    // -----------------------------------------------------------------------

    @Setup(Level.Invocation)
    public void seedRestingOrder() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.ASK, OrderType.LIMIT, 100, 1));
        // extract orderId so we can reference it if needed; discard event
        eventQueue.clear();
    }

    /**
     * Single aggressive BID against the pre-seeded ASK — measures end-to-end matching latency.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void latency_singleMatch() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.BID, OrderType.LIMIT, 100, 1));
        eventQueue.clear();
    }

    // -----------------------------------------------------------------------
    // Cancel-heavy: 50% submit, 50% cancel — stresses HashMap + TreeMap removal
    // -----------------------------------------------------------------------

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void cancelHeavy_submitThenCancel() {
        engine.processSync(new SubmitOrderCommand("BENCH", Side.ASK, OrderType.LIMIT, 101, 1));
        Event accepted = eventQueue.poll();
        if (accepted instanceof OrderAcceptedEvent e) {
            engine.processSync(new CancelOrderCommand(e.orderId(), "BENCH"));
            eventQueue.clear();
        }
    }
}
