# java-inventory-reservation

A concurrency-safe stock reservation service: the thing that prevents an
e-commerce flash sale from overselling the last unit of a product to five
customers at once.

Plain JDK only -- no Maven, no Spring, no external dependencies. Compiles
with `javac`, runs with `java`.

## The problem this solves

Naive checkout logic reads the stock count, checks "is it enough?", then
decrements. Under concurrent requests, two threads can both read the same
stock value, both decide there's enough, and both decrement -- overselling
by however many threads raced. This service closes that race with a
lock-free compare-and-swap loop, and adds automatic reservation expiry so
abandoned carts don't hold stock hostage forever.

## How it works

- `InventoryService.reserve(sku, qty)` -- atomically decrements available
  stock via a CAS loop on an `AtomicInteger`. A reservation only succeeds
  if the decrement actually lands; concurrent callers racing for the last
  unit cannot both win.
- `confirm(reservationId)` -- finalizes the sale; stock stays decremented.
- `release(reservationId)` -- cancels the hold, returns stock to the pool.
- `sweepExpired()` -- reclaims stock from reservations nobody confirmed or
  released within the TTL (default 5 minutes), run on a scheduler in
  `Main`.
- `Server` exposes this over HTTP using `com.sun.net.httpserver` (built
  into the JDK, zero framework dependency).

## Run it

```bash
./run.sh 8080
```

```bash
curl localhost:8080/inventory/sku-widget
# {"sku":"sku-widget","available":100}

curl -X POST localhost:8080/inventory/sku-widget/reserve -d '{"quantity":10}'
# {"reservationId":"..."}

curl -X POST localhost:8080/reservations/<id>/confirm
# {"confirmed":true}

curl -X POST localhost:8080/reservations/<id>/release
# {"released":true}
```

Over-reserving returns `409`:
```bash
curl -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/inventory/sku-gadget/reserve -d '{"quantity":999}'
# 409
```

## Tests

```bash
./test.sh
```

No JUnit -- a small hand-rolled harness (`InventoryServiceTest.main`) that
runs each test and reports PASS/FAIL, since the whole point of this
project was staying dependency-free. 6 tests:

- Single-threaded correctness: reserve+confirm, insufficient stock,
  release restores stock, expiry sweep restores stock.
- **`testConcurrentReservationsNeverOversell`** -- the actual point of
  this project. 50 threads, released simultaneously via a
  `CountDownLatch`, each attempt 200 one-unit reservations against a
  stock of 1000 (10,000 attempts contending for 1,000 units). Asserts
  successful reservations == exactly 1000, no more, no fewer. Ran this
  5 times back to back to rule out a race that only fails occasionally --
  passed every time.
- `testConcurrentConfirmReleaseInvariantHolds` -- 20 threads concurrently
  reserving, then confirming or releasing, while racing fresh reservations
  against the same SKU. Asserts `available + sold == initialStock` holds
  even under that contention.

## What's intentionally not here

No persistence (state is in-memory, lost on restart), no distributed
locking (this is single-process; a real multi-instance deployment would
need the CAS loop replaced with a database-level equivalent, e.g.
`UPDATE ... WHERE stock >= ? RETURNING ...` or Redis `WATCH`/`MULTI`), no
Maven/Spring -- kept intentionally small.
