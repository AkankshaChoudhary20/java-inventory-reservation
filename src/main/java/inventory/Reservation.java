package inventory;

/** An in-flight hold on stock: decremented from available, not yet sold or released. */
public final class Reservation {
    public final String id;
    public final String sku;
    public final int quantity;
    public final long expiresAtMillis;

    public Reservation(String id, String sku, int quantity, long expiresAtMillis) {
        this.id = id;
        this.sku = sku;
        this.quantity = quantity;
        this.expiresAtMillis = expiresAtMillis;
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }
}
