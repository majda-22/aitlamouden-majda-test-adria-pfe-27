package ma.enset.adria.transaction;

import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import ma.enset.adria.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:transaction-mcp-test;DB_CLOSE_DELAY=-1", "bank.transaction.recovery.enabled=false"})
class TransactionMcpTest {
    static final AccountStub SERVER = new AccountStub();
    @DynamicPropertySource static void url(DynamicPropertyRegistry r) { r.add("bank.account-service.url", SERVER::url); }
    @AfterAll static void stop() { SERVER.close(); }
    @Value("${local.server.port}") int port;
    @Autowired TransactionRepository repository;
    @Autowired CircuitBreakerRegistry circuits;
    @BeforeEach void reset() {
        repository.deleteAll(); SERVER.reset(); SERVER.respond(200,"ACTIVE","MAD","1500");
        circuits.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    }
    McpSyncClient connect() {
        var client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(10)).build(); client.initialize(); return client;
    }
    Map<String,Object> debit(String key) {
        return new HashMap<>(Map.of("accountId", UUID.randomUUID().toString(), "amount","250.00", "currency","MAD",
            "merchantCategory","SHOP", "country","MA", "idempotencyKey",key));
    }
    Map<String,Object> transfer(String key) {
        return new HashMap<>(Map.of("sourceAccountId",UUID.randomUUID().toString(),"destinationAccountId",UUID.randomUUID().toString(),
            "amount","250", "currency","MAD", "idempotencyKey",key));
    }
    McpSchema.CallToolResult call(McpSyncClient client,String name,Map<String,Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(name,args));
    }
    @SuppressWarnings("unchecked") String id(McpSchema.CallToolResult result) {
        return ((Map<String,Object>)result.structuredContent()).get("id").toString();
    }

    @Test void discoversExplicitSchemasAndExecutesIdempotentDebitAndTransfer() {
        try (var client=connect()) {
            var tools=client.listTools().tools();
            assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("get_transaction","list_transactions","get_transfer","create_transaction","create_transfer");
            for (var t:tools) if (t.name().startsWith("create_")) {
                assertThat(t.annotations().readOnlyHint()).isFalse();
                assertThat(t.inputSchema().get("required").toString()).contains("idempotencyKey", "amount");
                assertThat(t.inputSchema().get("properties").toString()).contains("type=string");
            }
            var debit=debit("mcp-debit"); var transfer=transfer("mcp-transfer");
            var d=call(client,"create_transaction",debit); var t=call(client,"create_transfer",transfer);
            assertThat(d.isError()).isFalse(); assertThat(t.isError()).isFalse();
            assertThat(d.structuredContent().toString()).contains("COMPLETED");
            assertThat(id(call(client,"create_transaction",debit))).isEqualTo(id(d));
            assertThat(id(call(client,"create_transfer",transfer))).isEqualTo(id(t));
            assertThat(call(client,"get_transaction",Map.of("id",id(d))).isError()).isFalse();
            assertThat(call(client,"get_transfer",Map.of("id",id(t))).isError()).isFalse();
            assertThat(call(client,"list_transactions",Map.of("accountId",debit.get("accountId"))).isError()).isFalse();
            assertThat(SERVER.balance()).isEqualByComparingTo("1000"); assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
            debit.put("amount","251"); var conflict=call(client,"create_transaction",debit);
            assertThat(conflict.isError()).isTrue(); assertThat(conflict.structuredContent().toString()).contains("IDEMPOTENCY_CONFLICT");
        }
    }
    @Test void invalidAmountsMissingAccountsAndRequiredKeyAreToolErrors() {
        try (var client=connect()) {
            for (Object amount:List.of("-1","0","1e3",250)) {
                var args=debit("invalid-"+amount); args.put("amount",amount);
                assertThat(call(client,"create_transaction",args).isError()).isTrue();
            }
            var args=debit("no-key"); args.remove("idempotencyKey");
            assertThat(call(client,"create_transaction",args).isError()).isTrue();
            SERVER.respond(404,"ACTIVE","MAD","1500");
            var missing=call(client,"create_transfer",transfer("absent"));
            assertThat(missing.isError()).isTrue(); assertThat(missing.structuredContent().toString()).contains("ACCOUNT_NOT_FOUND");
            assertThat(SERVER.balance()).isEqualByComparingTo("1500");
        }
    }
    @Test void unavailableAccountDoesNotInventCompletedMovement() {
        SERVER.respond(503,"ACTIVE","MAD","1500");
        try(var client=connect()) {
            var result=call(client,"create_transaction",debit("unavailable"));
            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContent().toString()).contains("PENDING").doesNotContain("COMPLETED");
            assertThat(SERVER.balance()).isEqualByComparingTo("1500");
        }
    }
}
