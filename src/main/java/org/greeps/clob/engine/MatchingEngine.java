package org.greeps.clob.engine;

import org.greeps.clob.book.OrderBook;
import org.greeps.clob.command.CancelOrderCommand;
import org.greeps.clob.command.Command;
import org.greeps.clob.command.ModifyOrderCommand;
import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.Order;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.event.*;
import org.greeps.clob.id.OrderIdGenerator;

import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

/**
 * Single-instrument matching engine.
 *
 * All book mutation runs exclusively on the engine's own thread via a
 * LinkedTransferQueue<Command> — no locks in the matching path.
 */
public final class MatchingEngine implements Runnable {

    private final String instrumentId;
    private final OrderBook book = new OrderBook();
    private final OrderIdGenerator idGenerator;
    private final Queue<Event> eventQueue;
    private final LinkedTransferQueue<Command> commandQueue = new LinkedTransferQueue<>();
    private volatile boolean running = false;
    private volatile Thread engineThread;
    private long nextTradeId = 1;

    public MatchingEngine(String instrumentId, OrderIdGenerator idGenerator, Queue<Event> eventQueue) {
        this.instrumentId = instrumentId;
        this.idGenerator = idGenerator;
        this.eventQueue = eventQueue;
    }

    // --- lifecycle ---

    public void start() {
        running = true;
        engineThread = Thread.ofPlatform()
                .name("engine-" + instrumentId)
                .start(this);
    }

    public void stop() {
        running = false;
        Thread t = engineThread;
        if (t != null) t.interrupt();
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Command cmd = commandQueue.poll(100, TimeUnit.MILLISECONDS);
                if (cmd != null) dispatch(cmd);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // --- public API: enqueue commands ---

    public void submit(SubmitOrderCommand cmd) {
        commandQueue.offer(cmd);
    }

    public void cancel(CancelOrderCommand cmd) {
        commandQueue.offer(cmd);
    }

    public void modify(ModifyOrderCommand cmd) {
        commandQueue.offer(cmd);
    }

    /**
     * Process a command synchronously on the calling thread, bypassing the command queue.
     * Use in unit tests and JMH benchmarks to isolate matching logic from threading overhead.
     */
    public void processSync(Command cmd) {
        dispatch(cmd);
    }

    // --- dispatch (runs on engine thread) ---

    private void dispatch(Command cmd) {
        switch (cmd) {
            case SubmitOrderCommand s -> handleSubmit(s);
            case CancelOrderCommand c -> handleCancel(c);
            case ModifyOrderCommand m -> handleModify(m);
        }
    }

    private void handleSubmit(SubmitOrderCommand cmd) {
        long orderId = idGenerator.next();
        long timestamp = System.nanoTime();

        Order order = new Order(orderId, cmd.instrumentId(), cmd.side(), cmd.type(),
                cmd.price(), cmd.quantity(), timestamp);

        eventQueue.offer(new OrderAcceptedEvent(orderId, instrumentId, cmd.side(), cmd.type(),
                cmd.price(), cmd.quantity(), timestamp));

        if (cmd.type() == OrderType.LIMIT) {
            matchLimit(order);
        } else {
            matchMarket(order);
        }
    }

    private void handleCancel(CancelOrderCommand cmd) {
        cancelById(cmd.orderId(), CancelReason.CANCELLED);
    }

    private void handleModify(ModifyOrderCommand cmd) {
        Order existing = book.find(cmd.orderId());
        if (existing == null) return;

        cancelById(cmd.orderId(), CancelReason.CANCELLED);

        handleSubmit(new SubmitOrderCommand(
                instrumentId,
                existing.side(),
                existing.type(),
                cmd.newPrice(),
                cmd.newQuantity()));
    }

    // --- matching ---

    private void matchLimit(Order incoming) {
        long lastFillPrice = 0;

        while (incoming.remaining() > 0 && crosses(incoming)) {
            Order resting = book.peekBestOpposite(incoming.side());
            if (resting == null) break;

            long fillQty = Math.min(incoming.remaining(), resting.remaining());
            long fillPrice = resting.price();
            long now = System.nanoTime();

            lastFillPrice = fillPrice;
            incoming.fill(fillQty);
            resting.fill(fillQty);

            long buyId  = incoming.side() == Side.BID ? incoming.orderId() : resting.orderId();
            long sellId = incoming.side() == Side.ASK ? incoming.orderId() : resting.orderId();

            eventQueue.offer(new TradeEvent(nextTradeId++, instrumentId, buyId, sellId,
                    fillPrice, fillQty, now));
            eventQueue.offer(new OrderFilledEvent(resting.orderId(), instrumentId,
                    fillQty, resting.remaining(), fillPrice, now));

            if (resting.remaining() == 0) {
                book.removeTopOfBook(resting.side());
            }
        }

        if (incoming.remaining() == 0) {
            eventQueue.offer(new OrderFilledEvent(incoming.orderId(), instrumentId,
                    incoming.quantity(), 0, lastFillPrice, System.nanoTime()));
        } else {
            book.add(incoming);
        }
    }

    private void matchMarket(Order incoming) {
        long lastFillPrice = 0;

        while (incoming.remaining() > 0) {
            Order resting = book.peekBestOpposite(incoming.side());
            if (resting == null) break;

            long fillQty = Math.min(incoming.remaining(), resting.remaining());
            long fillPrice = resting.price();
            long now = System.nanoTime();

            lastFillPrice = fillPrice;
            incoming.fill(fillQty);
            resting.fill(fillQty);

            long buyId  = incoming.side() == Side.BID ? incoming.orderId() : resting.orderId();
            long sellId = incoming.side() == Side.ASK ? incoming.orderId() : resting.orderId();

            eventQueue.offer(new TradeEvent(nextTradeId++, instrumentId, buyId, sellId,
                    fillPrice, fillQty, now));
            eventQueue.offer(new OrderFilledEvent(resting.orderId(), instrumentId,
                    fillQty, resting.remaining(), fillPrice, now));

            if (resting.remaining() == 0) {
                book.removeTopOfBook(resting.side());
            }
        }

        if (incoming.remaining() == 0) {
            eventQueue.offer(new OrderFilledEvent(incoming.orderId(), instrumentId,
                    incoming.quantity(), 0, lastFillPrice, System.nanoTime()));
        } else {
            incoming.cancel();
            eventQueue.offer(new OrderCancelledEvent(incoming.orderId(), instrumentId,
                    CancelReason.INSUFFICIENT_LIQUIDITY, System.nanoTime()));
        }
    }

    // --- cancel ---

    private void cancelById(long orderId, CancelReason reason) {
        Order order = book.remove(orderId);
        if (order == null) return;
        order.cancel();
        eventQueue.offer(new OrderCancelledEvent(orderId, instrumentId, reason, System.nanoTime()));
    }

    // --- crossing check ---

    private boolean crosses(Order incoming) {
        Long bestOpposite = book.bestOppositePrice(incoming.side());
        if (bestOpposite == null) return false;
        return incoming.side() == Side.BID
                ? incoming.price() >= bestOpposite
                : incoming.price() <= bestOpposite;
    }
}
