package org.greeps.clob.engine;

import org.greeps.clob.command.CancelOrderCommand;
import org.greeps.clob.command.ModifyOrderCommand;
import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.event.Event;
import org.greeps.clob.id.OrderIdGenerator;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entry point for all order operations.
 * Dispatches commands to the correct per-instrument MatchingEngine by instrumentId.
 */
public final class OrderRouter {

    private final OrderIdGenerator idGenerator;
    private final Queue<Event> eventQueue;
    private final Map<String, MatchingEngine> engines = new ConcurrentHashMap<>();

    public OrderRouter(OrderIdGenerator idGenerator, Queue<Event> eventQueue) {
        this.idGenerator = idGenerator;
        this.eventQueue = eventQueue;
    }

    /** Register a new instrument and start its matching engine. */
    public void registerInstrument(String instrumentId) {
        engines.computeIfAbsent(instrumentId, id -> {
            MatchingEngine engine = new MatchingEngine(id, idGenerator, eventQueue);
            engine.start();
            return engine;
        });
    }

    public void submit(SubmitOrderCommand cmd) {
        engine(cmd.instrumentId()).submit(cmd);
    }

    public void cancel(CancelOrderCommand cmd) {
        engine(cmd.instrumentId()).cancel(cmd);
    }

    public void modify(ModifyOrderCommand cmd) {
        engine(cmd.instrumentId()).modify(cmd);
    }

    /** Gracefully stop all engine threads. */
    public void shutdown() {
        engines.values().forEach(MatchingEngine::stop);
    }

    private MatchingEngine engine(String instrumentId) {
        MatchingEngine engine = engines.get(instrumentId);
        if (engine == null) throw new IllegalArgumentException("Unknown instrument: " + instrumentId);
        return engine;
    }
}
