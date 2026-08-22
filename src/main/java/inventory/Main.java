package inventory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        long ttlMillis = 5 * 60 * 1000; // 5 minutes

        InventoryService inventory = new InventoryService(ttlMillis);

        // Demo seed data -- a real deployment would load this from a catalog service.
        inventory.addStock("sku-widget", 100);
        inventory.addStock("sku-gadget", 25);

        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();
        sweeper.scheduleAtFixedRate(inventory::sweepExpired, 30, 30, TimeUnit.SECONDS);

        new Server(inventory).start(port);
        System.out.println("inventory-reservation-service listening on :" + port);
    }
}
