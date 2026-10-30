package ma.enset.adria.transaction;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

final class AccountStub implements AutoCloseable {
    record Request(String method, String path) { }
    private record Outcome(int code, String body) { }
    private record Reply(int httpStatus, String status, String currency, long delay, String raw) { }
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private final Map<String, Outcome> operations = new HashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile Reply reply;
    private BigDecimal balance;
    private BigDecimal destinationBalance;

    AccountStub() {
        try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
        catch (IOException e) { throw new IllegalStateException(e); }
        reset();
        server.setExecutor(executor);
        server.createContext("/internal/", exchange -> {
            Reply current = reply;
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath()));
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Outcome result = outcome(body, exchange.getRequestURI().getPath(), current);
                // Débit committé avant l'attente : reproduit une réponse perdue après succès.
                if (current.delay() > 0) Thread.sleep(current.delay());
                byte[] bytes = result.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.code(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* Timeout du client. */ }
            finally { exchange.close(); }
        });
        server.start();
    }

    private synchronized Outcome outcome(String body, String path, Reply current) {
        var data = json.readTree(body);
        String op = data.path("operationId").asText();
        if (current.raw() != null) return new Outcome(200, current.raw());
        if (current.httpStatus() >= 500) return new Outcome(current.httpStatus(), "{}");
        if (operations.containsKey(op)) return operations.get(op);
        BigDecimal amount = new BigDecimal(data.path("amount").asText());
        boolean transfer = path.equals("/internal/transfers");
        String refusal = transfer && data.path("sourceAccountId").asText().equals(data.path("destinationAccountId").asText()) ? "SAME_ACCOUNT" :
                current.httpStatus() == 404 ? "ACCOUNT_NOT_FOUND" :
                !"ACTIVE".equals(current.status()) ? "ACCOUNT_BLOCKED" :
                !current.currency().equals(data.path("currency").asText()) ? "CURRENCY_MISMATCH" :
                balance.compareTo(amount) < 0 ? "INSUFFICIENT_BALANCE" : null;
        Outcome result;
        if (refusal != null) result = new Outcome(refusal.equals("ACCOUNT_NOT_FOUND") ? 404 : 400,
                "{\"operationId\":\"" + op + "\",\"code\":\"" + refusal + "\"}");
        else {
            balance = balance.subtract(amount);
            if (transfer) {
                destinationBalance = destinationBalance.add(amount);
                result = new Outcome(200, "{\"operationId\":\"" + op + "\",\"sourceAccountId\":\""
                        + data.path("sourceAccountId").asText() + "\",\"destinationAccountId\":\""
                        + data.path("destinationAccountId").asText() + "\",\"amount\":" + amount
                        + ",\"currency\":\"" + data.path("currency").asText()
                        + "\",\"status\":\"COMPLETED\",\"sourceBalanceAfter\":" + balance
                        + ",\"destinationBalanceAfter\":" + destinationBalance + ",\"rejectionCode\":null}");
            } else result = new Outcome(200, "{\"operationId\":\"" + op + "\",\"accountId\":\""
                    + data.path("accountId").asText() + "\",\"amount\":" + amount + ",\"currency\":\""
                    + data.path("currency").asText() + "\",\"status\":\"COMPLETED\",\"balanceAfter\":"
                    + balance + ",\"rejectionCode\":null}");
        }
        operations.put(op, result);
        return result;
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    synchronized void reset() {
        reply = new Reply(200, "ACTIVE", "MAD", 0, null);
        balance = new BigDecimal("2000"); destinationBalance = new BigDecimal("200"); operations.clear(); requests.clear();
    }
    synchronized void respond(int httpStatus, String status, String currency, String balance) {
        this.balance = new BigDecimal(balance); reply = new Reply(httpStatus, status, currency, 0, null);
    }
    void delay(long millis) { reply = new Reply(200, "ACTIVE", "MAD", millis, null); }
    void available() { reply = new Reply(200, "ACTIVE", "MAD", 0, null); }
    void rawResponse(String body) { reply = new Reply(200, "ACTIVE", "MAD", 0, body); }
    List<Request> requests() { return List.copyOf(requests); }
    synchronized BigDecimal balance() { return balance; }
    synchronized BigDecimal destinationBalance() { return destinationBalance; }
    synchronized int operationCount() { return operations.size(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
