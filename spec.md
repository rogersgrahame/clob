# CLOB — Central Limit Order Book Specification

## Overview

A multi-instrument, in-memory Central Limit Order Book implemented in Java 23. Designed for low latency with production-quality internals. Phase 1 is in-memory only; persistence is deferred to Phase 2.

---

## Design Decisions

| Decision | Choice |
|---|---|
| Concurrency model | Single-threaded per instrument (sequenced) |
| Matching algorithm | Price-time priority (FIFO within price level) |
| Order types | Limit, Market |
| Operations | Submit, Cancel, Modify |
| Modify semantics | Cancel + re-submit (loses time priority) |
| Market order on thin book | Partial fill, cancel remainder |
| Trade event delivery | Async event queue |
| Price / quantity type | `long` (fixed-point ticks) |
| Order ID | Global monotonically increasing `AtomicLong` |
| Order routing | `OrderRouter` dispatches by instrument ID |
| Benchmarking | JMH |
| Collections | Standard Java (Phase 1); swap points clearly defined |
| Persistence | Out of scope (Phase 2) |

---

## Package Structure

```
org.greeps.clob
├── core/
│   ├── Order.java           # record — canonical order value object
│   ├── Side.java            # enum: BID, ASK
│   ├── OrderType.java       # enum: LIMIT, MARKET
│   └── OrderStatus.java     # enum: NEW, PARTIAL, FILLED, CANCELLED
├── book/
│   ├── OrderBook.java       # per-instrument book; owns bid + ask sides
│   └── PriceLevel.java      # price → ArrayDeque<Order>
├── engine/
│   ├── MatchingEngine.java  # sequenced loop per instrument
│   └── OrderRouter.java     # single entry point; dispatches to engines
├── command/
│   ├── Command.java         # sealed interface
│   ├── SubmitOrderCommand.java
│   ├── CancelOrderCommand.java
│   └── ModifyOrderCommand.java
├── event/
│   ├── Event.java           # sealed interface
│   ├── OrderAcceptedEvent.java
│   ├── OrderFilledEvent.java    # partial and full fills
│   ├── OrderCancelledEvent.java
│   └── TradeEvent.java
└── id/
    └── OrderIdGenerator.java    # AtomicLong; global across all instruments
```

JMH benchmarks live in a separate Gradle source set: `src/benchmark/java/org/greeps/clob/benchmark/`

---

## Core Data Model

### `Order` (record)

| Field | Type | Notes |
|---|---|---|
| `orderId` | `long` | Assigned by `OrderIdGenerator`; globally unique |
| `instrumentId` | `String` | e.g. `"AAPL"` |
| `side` | `Side` | `BID` or `ASK` |
| `type` | `OrderType` | `LIMIT` or `MARKET` |
| `price` | `long` | In ticks; ignored for `MARKET` orders |
| `quantity` | `long` | Original quantity |
| `remaining` | `long` | Decremented on each fill |
| `timestamp` | `long` | `System.nanoTime()` at submission |
| `status` | `OrderStatus` | `NEW`, `PARTIAL`, `FILLED`, `CANCELLED` |

### Events (sealed interface, records)

- **`OrderAcceptedEvent`** — orderId, instrumentId, side, type, price, quantity, timestamp
- **`OrderFilledEvent`** — orderId, instrumentId, fillQty, remainingQty, fillPrice, timestamp; `isFull()` convenience method
- **`OrderCancelledEvent`** — orderId, instrumentId, reason (`CANCELLED` | `INSUFFICIENT_LIQUIDITY`), timestamp
- **`TradeEvent`** — tradeId, instrumentId, buyOrderId, sellOrderId, price, quantity, timestamp

### Commands (sealed interface, records)

- **`SubmitOrderCommand`** — all Order fields except orderId (assigned by engine)
- **`CancelOrderCommand`** — orderId, instrumentId
- **`ModifyOrderCommand`** — orderId, instrumentId, newPrice, newQuantity

---

## OrderBook Internals

### Data structures (Phase 1 — swap points for Phase 2)

- **Bids**: `TreeMap<Long, ArrayDeque<Order>>` with `Comparator.reverseOrder()` — highest price first
- **Asks**: `TreeMap<Long, ArrayDeque<Order>>` — lowest price first (natural order)
- **Order index**: `HashMap<Long, Order>` — O(1) lookup by orderId for cancel/modify

`TreeMap` + `ArrayDeque` are the explicit swap targets. Phase 2 can introduce a price-indexed array or off-heap structure here without touching matching logic.

---

## Matching Engine

### Threading model

```
Client threads  →  LinkedTransferQueue<Command>  →  Engine thread  →  LinkedTransferQueue<Event>  →  Consumer thread(s)
```

- Each `MatchingEngine` owns one `Thread` and one inbound `LinkedTransferQueue<Command>`
- All book mutation is single-threaded — zero locks in the hot path
- Outbound events go to a shared `LinkedTransferQueue<Event>` injected at construction

### Limit order matching

1. Assign orderId (from `OrderIdGenerator`) and timestamp; emit `OrderAcceptedEvent`
2. While `remaining > 0` and book has crossing orders:
   - Determine fill qty = `min(incoming.remaining, resting.remaining)`
   - Decrement both remaining quantities
   - Emit `TradeEvent` (fill price = resting order's price)
   - Emit `OrderFilledEvent` for resting order
   - If resting order fully filled: remove from `ArrayDeque`; if level empty, remove from `TreeMap`
3. If `remaining > 0`: enqueue incoming order at the tail of its price level (time priority)
4. If `remaining == 0`: emit `OrderFilledEvent` (full) for incoming order

### Market order matching

Same as limit, without a price crossing check. If the book is exhausted before the order is fully filled, emit `OrderCancelledEvent` with reason `INSUFFICIENT_LIQUIDITY` for the remainder.

### Cancel

1. Look up order in `HashMap` — not found: no-op (idempotent)
2. Remove from `ArrayDeque` at its price level
3. If level empty, remove from `TreeMap`
4. Remove from `HashMap`
5. Emit `OrderCancelledEvent`

### Modify

1. Cancel the existing order (emits `OrderCancelledEvent`)
2. Submit a new command with updated price/quantity — receives a new orderId and timestamp, loses time priority

---

## OrderRouter

```java
Map<String, MatchingEngine> engines   // instrumentId → engine
```

| Method | Behaviour |
|---|---|
| `registerInstrument(String)` | Creates and starts a new `MatchingEngine` |
| `submit(SubmitOrderCommand)` | Enqueues on the correct engine's command queue |
| `cancel(CancelOrderCommand)` | Same |
| `modify(ModifyOrderCommand)` | Same |
| `shutdown()` | Graceful stop of all engine threads |

---

## JMH Benchmarks

Source set: `src/benchmark/java/org/greeps/clob/benchmark/`  
Gradle task: `./gradlew jmh`

| Benchmark | Mode | Focus |
|---|---|---|
| Throughput | `Throughput` | Sustained orders/sec — mixed limit + market, balanced buy/sell |
| Latency | `SampleTime` | p50 / p99 / p999 submit → TradeEvent round-trip |
| Cancel-heavy | `Throughput` | 50% submit, 50% cancel — stresses HashMap + TreeMap removal |

Configuration: 5 warmup iterations × 1s, 10 measurement iterations × 1s.

Baseline results with `TreeMap` / `ArrayDeque` are recorded so future collection swaps can be compared against them.

---

## Build

```bash
./gradlew build           # compile + test
./gradlew test            # run all tests
./gradlew test --tests "org.greeps.clob.SomeTest"  # single test class
./gradlew jmh             # run JMH benchmarks
```

Java 23, JUnit 5 (Jupiter), JMH 1.37.

---

## Out of Scope (Phase 1)

- Persistence / crash recovery
- Stop orders, iceberg orders, FOK / IOC flags
- Network API (FIX, WebSocket)
- Market data feed (Level 2 quotes)

---

## Implementation Order

1. Core domain — `Order`, enums, sealed event and command hierarchies
2. `OrderBook` — data structures and price level management
3. `MatchingEngine` — matching logic, single instrument, no threading yet
4. Tests — full fill, partial fill, cancel, modify, market order exhaustion
5. `OrderRouter` + threading — command queues, engine threads
6. End-to-end validation — simple logging consumer
7. JMH benchmark scaffolding + baseline measurement
