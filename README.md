# CLOB — Central Limit Order Book

A multi-instrument, price-time priority CLOB built in Java 23 as a learning project with production-quality intent. The design prioritises low latency and allocation elimination over simplicity.

## Features

- Limit and market orders with price-time (FIFO) priority
- Cancel and modify (cancel + re-submit semantics, loses time priority)
- Multiple instruments, each on its own sequenced engine thread
- Zero-allocation hot path: object pooling, primitive-keyed maps, MPSC/SPSC ring buffers, pre-allocated event slots

## Architecture

One `MatchingEngine` per instrument runs a single thread. Commands arrive via a lock-free MPSC ring buffer; events are published to a shared SPSC ring buffer. All book mutation is single-threaded — no locks in the matching path.

`OrderRouter` is the single external entry point and dispatches commands to the correct engine by instrument ID.

See `spec.md` for the full design rationale.

## Running

```bash
./gradlew build          # compile + test
./gradlew test           # run tests only
./gradlew jmh            # run JMH benchmarks
./gradlew run            # smoke-test main (submits two crossing orders on AAPL)
```

## Zero-GC Optimisations

| Step | Change | Eliminated |
|------|--------|------------|
| 1 | `OrderPool` | `Order` allocation per submit |
| 2 | `LongObjectHashMap` for order index | `Long` boxing on orderId lookups |
| 3–4 | MPSC/SPSC ring buffers + `MutableEvent` | Queue node and event record allocation |
| 5 | `PriceLadder` (sorted `long[]` + `LongObjectHashMap`) | `Long` boxing and `Map.Entry` allocation on every price-level operation |

## Tech Stack

- Java 23
- Eclipse Collections 11 (`LongObjectHashMap`)
- JMH 1.37 for benchmarking
- JUnit 5
