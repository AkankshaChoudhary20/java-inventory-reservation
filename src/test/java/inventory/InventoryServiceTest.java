package inventory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plain-JDK test harness (no JUnit dependency): each test method returns
 * normally on success or throws AssertionError on failure. main() runs
 * them all, prints PASS/FAIL, and exits non-zero if anything failed.
 */
public final class InventoryServiceTest {

    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += runTest("testConfirmRejectsExpiredReservation", InventoryServiceTest::testConfirmRejectsExpiredReservation);
        failures += runTest("testReserveConfirm", InventoryServiceTest::testReserveConfirm);
        failures += runTest("testReserveInsufficientStock", InventoryServiceTest::testReserveInsufficientStock);
        failures += runTest("testReleaseReturnsStock", InventoryServiceTest::testReleaseReturnsStock);
        failures += runTest("testExpirySweepReturnsStock", InventoryServiceTest::testExpirySweepReturnsStock);
        failures += runFallibleTest("testConcurrentReservationsNeverOversell",
                InventoryServiceTest::testConcurrentReservationsNeverOversell);
        failures += runFallibleTest("testConcurrentConfirmReleaseInvariantHolds",
                InventoryServiceTest::testConcurrentConfirmReleaseInvariantHolds);

        if (failures > 0) {
            System.out.println("\n" + failures + " test(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nALL TESTS PASSED");
    }

    static void testConfirmRejectsExpiredReservation() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1000);
        InventoryService service = new InventoryService(1000, now::get);
        service.addStock("sku", 1);
        String expired = service.reserve("sku", 1).reservationId;
        now.set(2000);
        assertTrue(!service.confirm(expired), "expired reservation must not become a sale");
        assertEquals(1, service.availableStock("sku"), "expired stock returned immediately");
        assertEquals(0, service.totalSold(), "expired confirmation cannot increment sales");
        assertTrue(!service.confirm(expired), "expired reservation cannot be finalized twice");
        assertEquals(1, service.availableStock("sku"), "stock must not be returned twice");
        String valid = service.reserve("sku", 1).reservationId;
        now.set(2999);
        assertTrue(service.confirm(valid), "reservation remains valid before its deadline");
    }

    private interface FallibleTest {
        void run() throws Exception;
    }

    private static int runTest(String name, Runnable test) {
        try {
            test.run();
            System.out.println("OK:   " + name);
            return 0;
        } catch (AssertionError e) {
            System.out.println("FAIL: " + name + " -- " + e.getMessage());
            return 1;
        }
    }

    private static int runFallibleTest(String name, FallibleTest test) {
        try {
            test.run();
            System.out.println("OK:   " + name);
            return 0;
        } catch (AssertionError e) {
            System.out.println("FAIL: " + name + " -- " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.out.println("FAIL: " + name + " -- unexpected exception: " + e);
            return 1;
        }
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // -- single-threaded correctness --

    static void testReserveConfirm() {
        InventoryService inv = new InventoryService(60_000);
        inv.addStock("sku", 10);

        InventoryService.ReservationResult r = inv.reserve("sku", 4);
        assertTrue(r.success, "reservation should succeed with enough stock");
        assertEquals(6, inv.availableStock("sku"), "stock should be decremented on reserve");

        boolean confirmed = inv.confirm(r.reservationId);
        assertTrue(confirmed, "confirm should succeed for a valid reservation id");
        assertEquals(6, inv.availableStock("sku"), "stock should stay decremented after confirm (it's sold)");
        assertEquals(4, inv.totalSold(), "confirmed quantity should count as sold");
        assertEquals(0, inv.activeReservationCount(), "confirmed reservation should no longer be active");
    }

    static void testReserveInsufficientStock() {
        InventoryService inv = new InventoryService(60_000);
        inv.addStock("sku", 3);

        InventoryService.ReservationResult r = inv.reserve("sku", 10);
        assertTrue(!r.success, "reservation should fail when quantity exceeds stock");
        assertEquals(3, inv.availableStock("sku"), "stock must be unchanged after a failed reservation");
    }

    static void testReleaseReturnsStock() {
        InventoryService inv = new InventoryService(60_000);
        inv.addStock("sku", 10);

        InventoryService.ReservationResult r = inv.reserve("sku", 5);
        assertEquals(5, inv.availableStock("sku"), "stock decremented after reserve");

        boolean released = inv.release(r.reservationId);
        assertTrue(released, "release should succeed for a valid reservation id");
        assertEquals(10, inv.availableStock("sku"), "stock should be fully restored after release");
        assertEquals(0, inv.totalSold(), "a released reservation must never count as sold");
    }

    static void testExpirySweepReturnsStock() {
        InventoryService inv = new InventoryService(1); // 1ms TTL -- expires almost immediately
        inv.addStock("sku", 10);

        inv.reserve("sku", 6);
        assertEquals(4, inv.availableStock("sku"), "stock decremented after reserve");

        try {
            Thread.sleep(20);
        } catch (InterruptedException ignored) {
        }

        int reclaimed = inv.sweepExpired();
        assertEquals(1, reclaimed, "sweep should reclaim exactly the one expired reservation");
        assertEquals(10, inv.availableStock("sku"), "stock should be fully restored after expiry sweep");
        assertEquals(0, inv.activeReservationCount(), "no reservations should remain active after sweep");
    }

    // -- the real point of this project: concurrency correctness --

    /**
     * 50 threads each attempt 200 reservations of 1 unit against a stock of
     * 1000 -- 10,000 total attempts contending for 1,000 units. If the
     * reserve() CAS loop has any race, this either oversells (more than
     * 1000 successes) or undersells (fewer than 1000 successes while stock
     * remains available).
     */
    static void testConcurrentReservationsNeverOversell() throws InterruptedException {
        final int initialStock = 1000;
        final int threadCount = 50;
        final int attemptsPerThread = 200;

        InventoryService inv = new InventoryService(60_000);
        inv.addStock("sku", initialStock);

        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (inv.reserve("sku", 1).success) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads at once to maximize contention
        doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        if (firstError.get() != null) {
            throw new AssertionError("worker thread threw: " + firstError.get());
        }

        assertEquals(initialStock, successCount.get(),
                "successful reservations must exactly equal initial stock -- no overselling, no lost stock");
        assertEquals(0, inv.availableStock("sku"), "all stock should be reserved");
        assertEquals(initialStock, inv.activeReservationCount(),
                "one active reservation per successful 1-unit reserve() call");
    }

    /**
     * Same contention, but this time every successful reservation is
     * immediately confirmed (sold) or released (returned) by another
     * concurrent thread. Checks the accounting invariant
     * available + sold == initialStock holds even while confirm/release
     * race against fresh reserve() calls on the same SKU.
     */
    static void testConcurrentConfirmReleaseInvariantHolds() throws InterruptedException {
        final int initialStock = 500;
        final int threadCount = 20;
        final int attemptsPerThread = 100;

        InventoryService inv = new InventoryService(60_000);
        inv.addStock("sku", initialStock);

        AtomicReference<Throwable> firstError = new AtomicReference<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        InventoryService.ReservationResult r = inv.reserve("sku", 1);
                        if (!r.success) continue;
                        if ((seed + i) % 2 == 0) {
                            inv.confirm(r.reservationId);
                        } else {
                            inv.release(r.reservationId);
                        }
                    }
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        if (firstError.get() != null) {
            throw new AssertionError("worker thread threw: " + firstError.get());
        }

        assertEquals(0, inv.activeReservationCount(), "every reservation should have been confirmed or released");
        int accounted = inv.availableStock("sku") + inv.totalSold();
        assertEquals(initialStock, accounted,
                "available + sold must equal initial stock: available=" + inv.availableStock("sku") + " sold=" + inv.totalSold());
    }
}
