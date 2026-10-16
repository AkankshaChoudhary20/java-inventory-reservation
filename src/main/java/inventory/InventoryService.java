package inventory;

import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency-safe stock reservation.
 *
 * <p>The problem: under concurrent checkout traffic, naive
 * "read stock, check if enough, decrement" logic races -- two threads can
 * both read stock=1, both decide "enough", and both decrement, overselling
 * by one unit. This service avoids that with a lock-free compare-and-swap
 * loop on an {@link AtomicInteger} per SKU, so a reservation only succeeds
 * if the decrement actually lands.
 *
 * <p>Reservations expire automatically (default 5 minutes) if never
 * confirmed or released, so an abandoned cart doesn't hold stock hostage
 * forever -- call {@link #sweepExpired()} periodically (or let
 * {@link Main} do it via a scheduler) to reclaim it.
 */
public final class InventoryService {

    private final Map<String, AtomicInteger> stock = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final AtomicInteger totalSold = new AtomicInteger(0);
    private final long reservationTtlMillis;
    private final LongSupplier clock;

    public InventoryService(long reservationTtlMillis) {
        this(reservationTtlMillis, System::currentTimeMillis);
    }

    InventoryService(long reservationTtlMillis, LongSupplier clock) {
        this.reservationTtlMillis = reservationTtlMillis;
        this.clock = clock;
    }

    public void addStock(String sku, int quantity) {
        stock.computeIfAbsent(sku, k -> new AtomicInteger(0)).addAndGet(quantity);
    }

    public int availableStock(String sku) {
        AtomicInteger counter = stock.get(sku);
        return counter == null ? 0 : counter.get();
    }

    public int totalSold() {
        return totalSold.get();
    }

    public int activeReservationCount() {
        return reservations.size();
    }

    public static final class ReservationResult {
        public final boolean success;
        public final String reservationId;
        public final String message;

        private ReservationResult(boolean success, String reservationId, String message) {
            this.success = success;
            this.reservationId = reservationId;
            this.message = message;
        }

        static ReservationResult ok(String id) {
            return new ReservationResult(true, id, "reserved");
        }

        static ReservationResult failed(String message) {
            return new ReservationResult(false, null, message);
        }
    }

    /**
     * Atomically reserves {@code quantity} units of {@code sku} if enough
     * stock is available. Never oversells under concurrent callers: the
     * decrement is a CAS loop, so two racing reservations for the last unit
     * cannot both succeed.
     */
    public ReservationResult reserve(String sku, int quantity) {
        if (quantity <= 0) {
            return ReservationResult.failed("quantity must be positive");
        }
        AtomicInteger counter = stock.get(sku);
        if (counter == null) {
            return ReservationResult.failed("unknown sku: " + sku);
        }

        while (true) {
            int current = counter.get();
            if (current < quantity) {
                return ReservationResult.failed("insufficient stock");
            }
            if (counter.compareAndSet(current, current - quantity)) {
                break;
            }
            // another thread won the race on this SKU's counter; retry with a fresh read
        }

        String id = UUID.randomUUID().toString();
        long expiresAt = clock.getAsLong() + reservationTtlMillis;
        reservations.put(id, new Reservation(id, sku, quantity, expiresAt));
        return ReservationResult.ok(id);
    }

    /**
     * Finalizes a reservation as a completed sale. Stock stays decremented
     * (it's sold, not returned to the pool).
     */
    public boolean confirm(String reservationId) {
        Reservation r = reservations.remove(reservationId);
        if (r == null) {
            return false;
        }
        if (r.isExpired(clock.getAsLong())) {
            stock.get(r.sku).addAndGet(r.quantity);
            return false;
        }
        totalSold.addAndGet(r.quantity);
        return true;
    }

    /** Cancels a reservation and returns its quantity to available stock. */
    public boolean release(String reservationId) {
        Reservation r = reservations.remove(reservationId);
        if (r == null) {
            return false;
        }
        stock.get(r.sku).addAndGet(r.quantity);
        return true;
    }

    /**
     * Reclaims stock held by reservations that expired without being
     * confirmed or released. Returns how many were reclaimed.
     */
    public int sweepExpired() {
        long now = clock.getAsLong();
        int reclaimed = 0;
        for (Reservation r : reservations.values()) {
            if (r.isExpired(now) && reservations.remove(r.id, r)) {
                stock.get(r.sku).addAndGet(r.quantity);
                reclaimed++;
            }
        }
        return reclaimed;
    }
}
