package inventory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal REST layer over InventoryService using the JDK's built-in HTTP server -- no framework dependency. */
public final class Server {

    private static final Pattern RESERVE_PATH = Pattern.compile("^/inventory/([^/]+)/reserve$");
    private static final Pattern STOCK_PATH = Pattern.compile("^/inventory/([^/]+)$");
    private static final Pattern CONFIRM_PATH = Pattern.compile("^/reservations/([^/]+)/confirm$");
    private static final Pattern RELEASE_PATH = Pattern.compile("^/reservations/([^/]+)/release$");

    private final InventoryService inventory;

    public Server(InventoryService inventory) {
        this.inventory = inventory;
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            Matcher m;
            if (method.equals("POST") && (m = RESERVE_PATH.matcher(path)).matches()) {
                handleReserve(exchange, m.group(1));
            } else if (method.equals("GET") && (m = STOCK_PATH.matcher(path)).matches()) {
                handleStock(exchange, m.group(1));
            } else if (method.equals("POST") && (m = CONFIRM_PATH.matcher(path)).matches()) {
                boolean ok = inventory.confirm(m.group(1));
                respond(exchange, ok ? 200 : 404, "{\"confirmed\":" + ok + "}");
            } else if (method.equals("POST") && (m = RELEASE_PATH.matcher(path)).matches()) {
                boolean ok = inventory.release(m.group(1));
                respond(exchange, ok ? 200 : 404, "{\"released\":" + ok + "}");
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            respond(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleReserve(HttpExchange exchange, String sku) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int qty = extractIntField(body, "quantity", 1);

        InventoryService.ReservationResult result = inventory.reserve(sku, qty);
        if (result.success) {
            respond(exchange, 201, "{\"reservationId\":\"" + result.reservationId + "\"}");
        } else {
            respond(exchange, 409, "{\"error\":\"" + result.message + "\"}");
        }
    }

    private void handleStock(HttpExchange exchange, String sku) throws IOException {
        int available = inventory.availableStock(sku);
        respond(exchange, 200, "{\"sku\":\"" + sku + "\",\"available\":" + available + "}");
    }

    private static int extractIntField(String json, String field, int fallback) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static void respond(HttpExchange exchange, int status, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
