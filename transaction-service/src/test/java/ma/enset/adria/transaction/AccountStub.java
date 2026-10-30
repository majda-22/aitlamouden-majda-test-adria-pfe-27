package ma.enset.adria.transaction;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AccountStub implements AutoCloseable {

    record Request(String method, String path) { }

    private record Reply(int status, String accountStatus, String currency, String balance,
                         long delayMillis, String rawBody) { }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private volatile Reply reply;

    AccountStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible de démarrer le serveur HTTP de test", exception);
        }
        reset();
        server.setExecutor(executor);
        server.createContext("/api/accounts/", exchange -> {
            Reply current = reply;
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath()));
            try {
                if (current.delayMillis() > 0) {
                    Thread.sleep(current.delayMillis());
                }
                String path = exchange.getRequestURI().getPath();
                String id = path.substring(path.lastIndexOf('/') + 1);
                String body = current.rawBody() != null ? current.rawBody() :
                        "{\"id\":\"" + id + "\",\"customerReference\":\"CLIENT-STUB\",\"status\":\""
                                + current.accountStatus() + "\",\"currency\":\"" + current.currency()
                                + "\",\"balance\":" + current.balance() + "}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(current.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Le client peut fermer la connexion lors du test de timeout.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        reply = new Reply(200, "ACTIVE", "MAD", "2000.00", 0, null);
        requests.clear();
    }

    void respond(int httpStatus, String status, String currency, String balance) {
        reply = new Reply(httpStatus, status, currency, balance, 0, null);
    }

    void delay(long millis) {
        reply = new Reply(200, "ACTIVE", "MAD", "2000.00", millis, null);
    }

    void rawResponse(String body) {
        reply = new Reply(200, "ACTIVE", "MAD", "2000.00", 0, body);
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
