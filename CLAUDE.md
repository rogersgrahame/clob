# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build                                          # compile + test
./gradlew test                                           # run all tests
./gradlew test --tests "org.greeps.clob.SomeTest"        # single test class
./gradlew jmh                                            # run JMH benchmarks
```

## Architecture

This is a multi-instrument Central Limit Order Book (CLOB) — see `spec.md` for the full design. Key structural points:

**Concurrency**: one thread per instrument. `MatchingEngine` owns a `LinkedTransferQueue<Command>` inbound and writes to a shared `LinkedTransferQueue<Event>` outbound. All book mutation is single-threaded — no locks in the matching path.

**Order routing**: `OrderRouter` is the single external entry point. It holds a `Map<String, MatchingEngine>` and dispatches `SubmitOrderCommand`, `CancelOrderCommand`, and `ModifyOrderCommand` to the correct engine by instrument ID.

**Order IDs**: globally unique, assigned by `OrderIdGenerator` (`AtomicLong` shared across all instruments).

**Price and quantity**: always `long` (fixed-point ticks). Never `double` or `BigDecimal`.

**Book internals** (`OrderBook`): bids in `TreeMap<Long, ArrayDeque<Order>>` (reversed), asks in natural order. `HashMap<Long, Order>` for O(1) cancel/modify. These collections are the explicit swap targets for Phase 2 performance work — do not change the matching logic when swapping them.

**Modify semantics**: cancel + re-submit. The modified order gets a new orderId and timestamp and loses time priority.

**Events** (`event/`): sealed interface hierarchy — `OrderAcceptedEvent`, `OrderFilledEvent`, `OrderCancelledEvent`, `TradeEvent`. `OrderFilledEvent` covers both partial and full fills; use `isFull()` to distinguish.
