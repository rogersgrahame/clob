package org.greeps.clob.engine;

import org.greeps.clob.book.OrderBook;
import org.greeps.clob.command.CancelOrderCommand;
import org.greeps.clob.command.Command;
import org.greeps.clob.command.ModifyOrderCommand;
import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.Order;
import org.greeps.clob.core.OrderPool;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.event.CancelReason;
import org.greeps.clob.id.OrderIdGenerator;
import org.greeps.clob.ring.*;

/**
 * Single-instrument matching engine.
 *
 * Commands arrive via a CommandRingBuffer (MPSC, zero allocation).
 * Events are published to an EventRingBuffer (SPSC, zero allocation).
 * All book mutation is single-threaded — no locks in the matching path.
 */
public final class MatchingEngine implements Runnable {

    private static final int DEFAULT_POOL_CAPACITY    = 1024;
    private static final int DEFAULT_COMMAND_CAPACITY = 4096;

    private final String           instrumentId;
    private final OrderBook        book       = new OrderBook();
    private final OrderIdGenerator idGenerator;
    private final EventRingBuffer  eventRing;
    private final CommandRingBuffer commandRing;
    private final OrderPool        orderPool;
    private volatile boolean       running   = false;
    private volatile Thread        engineThread;
    private long                   nextTradeId = 1;

    public MatchingEngine(String instrumentId, OrderIdGenerator idGenerator,
                          EventRingBuffer eventRing) {
        this(instrumentId, idGenerator, eventRing,
             new OrderPool(DEFAULT_POOL_CAPACITY),
             new CommandRingBuffer(DEFAULT_COMMAND_CAPACITY));
    }

    public MatchingEngine(String instrumentId, OrderIdGenerator idGenerator,
                          EventRingBuffer eventRing, OrderPool orderPool,
                          CommandRingBuffer commandRing) {
        this.instrumentId = instrumentId;
        this.idGenerator  = idGenerator;
        this.eventRing    = eventRing;
        this.orderPool    = orderPool;
        this.commandRing  = commandRing;
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
            CommandSlot slot = commandRing.poll();
            if (slot == null) { Thread.onSpinWait(); continue; }
            dispatchSlot(slot);
            commandRing.done(slot);
        }
    }

    // --- public API: enqueue commands to ring buffer ---

    public void submit(SubmitOrderCommand cmd) {
        CommandSlot slot = commandRing.claim();
        slot.commandType  = CommandType.SUBMIT;
        slot.instrumentId = cmd.instrumentId();
        slot.side         = cmd.side();
        slot.orderType    = cmd.type();
        slot.price        = cmd.price();
        slot.quantity     = cmd.quantity();
        commandRing.publish(slot);
    }

    public void cancel(CancelOrderCommand cmd) {
        CommandSlot slot = commandRing.claim();
        slot.commandType = CommandType.CANCEL;
        slot.orderId     = cmd.orderId();
        commandRing.publish(slot);
    }

    public void modify(ModifyOrderCommand cmd) {
        CommandSlot slot = commandRing.claim();
        slot.commandType  = CommandType.MODIFY;
        slot.orderId      = cmd.orderId();
        slot.newPrice     = cmd.newPrice();
        slot.newQuantity  = cmd.newQuantity();
        commandRing.publish(slot);
    }

    /**
     * Process a command synchronously on the calling thread, bypassing the ring buffer.
     * Use in unit tests and JMH benchmarks to isolate matching logic from threading overhead.
     */
    public void processSync(Command cmd) {
        switch (cmd) {
            case SubmitOrderCommand s -> processSubmit(s.instrumentId(), s.side(), s.type(),
                                                       s.price(), s.quantity());
            case CancelOrderCommand c -> processCancelById(c.orderId(), CancelReason.CANCELLED);
            case ModifyOrderCommand m -> processModify(m.orderId(), m.newPrice(), m.newQuantity());
        }
    }

    // --- ring buffer dispatch (engine thread) ---

    private void dispatchSlot(CommandSlot slot) {
        switch (slot.commandType) {
            case SUBMIT -> processSubmit(slot.instrumentId, slot.side, slot.orderType,
                                         slot.price, slot.quantity);
            case CANCEL -> processCancelById(slot.orderId, CancelReason.CANCELLED);
            case MODIFY -> processModify(slot.orderId, slot.newPrice, slot.newQuantity);
        }
    }

    // --- core handlers ---

    private void processSubmit(String instrId, Side side, OrderType type,
                                long price, long quantity) {
        long orderId   = idGenerator.next();
        long timestamp = System.nanoTime();
        Order order    = orderPool.borrow(orderId, instrId, side, type, price, quantity, timestamp);

        publishOrderAccepted(orderId, side, type, price, quantity, timestamp);

        if (type == OrderType.LIMIT) matchLimit(order);
        else                          matchMarket(order);
    }

    private void processModify(long orderId, long newPrice, long newQuantity) {
        Order existing = book.find(orderId);
        if (existing == null) return;

        // Capture before cancel returns order to pool
        Side      side = existing.side();
        OrderType type = existing.type();

        processCancelById(orderId, CancelReason.CANCELLED);
        processSubmit(instrumentId, side, type, newPrice, newQuantity);
    }

    // --- matching ---

    private void matchLimit(Order incoming) {
        long lastFillPrice = 0;

        while (incoming.remaining() > 0 && crosses(incoming)) {
            Order resting = book.peekBestOpposite(incoming.side());
            if (resting == null) break;

            long fillQty   = Math.min(incoming.remaining(), resting.remaining());
            long fillPrice = resting.price();
            long now       = System.nanoTime();

            lastFillPrice = fillPrice;
            incoming.fill(fillQty);
            resting.fill(fillQty);

            long buyId  = incoming.side() == Side.BID ? incoming.orderId() : resting.orderId();
            long sellId = incoming.side() == Side.ASK ? incoming.orderId() : resting.orderId();

            publishTrade(nextTradeId++, buyId, sellId, fillPrice, fillQty, now);
            publishOrderFilled(resting.orderId(), fillQty, resting.remaining(), fillPrice, now);

            if (resting.remaining() == 0) {
                book.removeTopOfBook(resting.side());
                orderPool.release(resting);
            }
        }

        if (incoming.remaining() == 0) {
            publishOrderFilled(incoming.orderId(), incoming.quantity(), 0, lastFillPrice,
                               System.nanoTime());
            orderPool.release(incoming);
        } else {
            book.add(incoming);
        }
    }

    private void matchMarket(Order incoming) {
        long lastFillPrice = 0;

        while (incoming.remaining() > 0) {
            Order resting = book.peekBestOpposite(incoming.side());
            if (resting == null) break;

            long fillQty   = Math.min(incoming.remaining(), resting.remaining());
            long fillPrice = resting.price();
            long now       = System.nanoTime();

            lastFillPrice = fillPrice;
            incoming.fill(fillQty);
            resting.fill(fillQty);

            long buyId  = incoming.side() == Side.BID ? incoming.orderId() : resting.orderId();
            long sellId = incoming.side() == Side.ASK ? incoming.orderId() : resting.orderId();

            publishTrade(nextTradeId++, buyId, sellId, fillPrice, fillQty, now);
            publishOrderFilled(resting.orderId(), fillQty, resting.remaining(), fillPrice, now);

            if (resting.remaining() == 0) {
                book.removeTopOfBook(resting.side());
                orderPool.release(resting);
            }
        }

        if (incoming.remaining() == 0) {
            publishOrderFilled(incoming.orderId(), incoming.quantity(), 0, lastFillPrice,
                               System.nanoTime());
            orderPool.release(incoming);
        } else {
            incoming.cancel();
            publishOrderCancelled(incoming.orderId(), CancelReason.INSUFFICIENT_LIQUIDITY,
                                  System.nanoTime());
            orderPool.release(incoming);
        }
    }

    // --- cancel ---

    private void processCancelById(long orderId, CancelReason reason) {
        Order order = book.remove(orderId);
        if (order == null) return;
        order.cancel();
        publishOrderCancelled(orderId, reason, System.nanoTime());
        orderPool.release(order);
    }

    // --- crossing check ---

    private boolean crosses(Order incoming) {
        long best = book.bestOppositePrice(incoming.side());
        if (best == OrderBook.NO_PRICE) return false;
        return incoming.side() == Side.BID ? incoming.price() >= best : incoming.price() <= best;
    }

    // --- event publish helpers (claim slot → write fields → volatile publish) ---

    private void publishOrderAccepted(long orderId, Side side, OrderType type,
                                      long price, long qty, long ts) {
        MutableEvent e  = eventRing.claim();
        e.eventType     = EventType.ORDER_ACCEPTED;
        e.orderId       = orderId;
        e.instrumentId  = instrumentId;
        e.side          = side;
        e.orderType     = type;
        e.price         = price;
        e.quantity      = qty;
        e.timestamp     = ts;
        eventRing.publish(e);
    }

    private void publishTrade(long tradeId, long buyId, long sellId,
                              long price, long qty, long ts) {
        MutableEvent e  = eventRing.claim();
        e.eventType     = EventType.TRADE;
        e.tradeId       = tradeId;
        e.instrumentId  = instrumentId;
        e.buyOrderId    = buyId;
        e.sellOrderId   = sellId;
        e.price         = price;
        e.quantity      = qty;
        e.timestamp     = ts;
        eventRing.publish(e);
    }

    private void publishOrderFilled(long orderId, long fillQty, long remainingQty,
                                    long fillPrice, long ts) {
        MutableEvent e  = eventRing.claim();
        e.eventType     = EventType.ORDER_FILLED;
        e.orderId       = orderId;
        e.instrumentId  = instrumentId;
        e.fillQty       = fillQty;
        e.remainingQty  = remainingQty;
        e.fillPrice     = fillPrice;
        e.timestamp     = ts;
        eventRing.publish(e);
    }

    private void publishOrderCancelled(long orderId, CancelReason reason, long ts) {
        MutableEvent e  = eventRing.claim();
        e.eventType     = EventType.ORDER_CANCELLED;
        e.orderId       = orderId;
        e.instrumentId  = instrumentId;
        e.cancelReason  = reason;
        e.timestamp     = ts;
        eventRing.publish(e);
    }
}
