package ma.enset.adria.transaction;

import io.github.resilience4j.circuitbreaker.*;
import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.repository.TransactionRepository;
import ma.enset.adria.transaction.service.TransactionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:transaction-graphql-test;DB_CLOSE_DELAY=-1",
        "bank.transaction.recovery.enabled=false", "spring.cloud.openfeign.client.config.account-service.readTimeout=200"})
@AutoConfigureMockMvc
class TransactionGraphQlTest {
    static final AccountStub SERVER = new AccountStub();
    @DynamicPropertySource static void url(DynamicPropertyRegistry registry) { registry.add("bank.account-service.url", SERVER::url); }
    @AfterAll static void stop() { SERVER.close(); }
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired TransactionRepository repository;
    @Autowired CircuitBreakerRegistry circuits;
    @Autowired TransactionService service;
    UUID accountId;
    UUID destinationId;
    static final String CREATE = "mutation($input: CreateTransactionInput!, $key: String!) { createTransaction(input: $input, idempotencyKey: $key) { id accountId amount currency createdAt status rejectionCode } }";
    static final String TRANSFER = "mutation($input: CreateTransferInput!, $key: String!) { createTransfer(input: $input, idempotencyKey: $key) { id sourceAccountId destinationAccountId amount createdAt status rejectionCode } }";

    @BeforeEach void reset() {
        repository.deleteAll(); SERVER.reset(); SERVER.respond(200, "ACTIVE", "MAD", "1500");
        circuits.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        accountId = UUID.randomUUID(); destinationId = UUID.randomUUID();
    }
    Map<String, Object> debit() {
        return new HashMap<>(Map.of("accountId", accountId.toString(), "amount", "250.00", "currency", "MAD", "merchantCategory", "SHOP", "country", "MA"));
    }
    Map<String, Object> transfer() {
        return new HashMap<>(Map.of("sourceAccountId", accountId.toString(), "destinationAccountId", destinationId.toString(), "amount", "250.00", "currency", "MAD"));
    }
    ResultActions graph(String query, Map<String, Object> variables) throws Exception {
        var result = mvc.perform(post("/graphql").contentType("application/json").accept("application/json")
                .content(json.writeValueAsString(Map.of("query", query, "variables", variables))));
        var initial = result.andReturn();
        if (initial.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(initial));
        return result.andExpect(status().isOk());
    }
    String id(ResultActions action, String field) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(action.andReturn().getResponse().getContentAsString(), "$.data." + field + ".id");
    }

    @Test void createsReadsAndReplaysDebitThroughExistingService() throws Exception {
        var vars = Map.<String,Object>of("input", debit(), "key", "gql-debit");
        var created = graph(CREATE, vars).andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createTransaction.status").value("COMPLETED"));
        String id = id(created, "createTransaction");
        graph(CREATE, vars).andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createTransaction.id").value(id));
        graph("query($id: ID!) { transaction(id: $id) { id accountId amount createdAt status } }", Map.of("id", id))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.transaction.id").value(id))
                .andExpect(jsonPath("$.data.transaction.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.data.transaction.amount").isString());
        assertThat(Instant.parse(service.get(UUID.fromString(id)).createdAt().toString())).isNotNull();
        mvc.perform(get("/api/transactions/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(SERVER.requests()).containsExactly(new AccountStub.Request("POST", "/internal/debits"));
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
    }

    @Test void createsReadsAndReplaysTransferWithoutDoubleMovement() throws Exception {
        var vars = Map.<String,Object>of("input", transfer(), "key", "gql-transfer");
        String id = id(graph(TRANSFER, vars).andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createTransfer.status").value("COMPLETED")), "createTransfer");
        graph(TRANSFER, vars).andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createTransfer.id").value(id));
        graph("query($id: ID!) { transfer(id: $id) { id sourceAccountId destinationAccountId status } }", Map.of("id", id))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.transfer.destinationAccountId").value(destinationId.toString()));
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
        assertThat(SERVER.requests()).containsExactly(new AccountStub.Request("POST", "/internal/transfers"));
    }

    @Test void paginationHasSameDefaultsAndAccountFilterAsRest() throws Exception {
        graph(CREATE, Map.of("input", debit(), "key", "first")).andExpect(jsonPath("$.errors").doesNotExist());
        graph(CREATE, Map.of("input", debit(), "key", "second")).andExpect(jsonPath("$.errors").doesNotExist());
        graph("query($id: ID!) { transactions(accountId: $id) { page size totalElements content { id } } }", Map.of("id", accountId.toString()))
                .andExpect(jsonPath("$.errors").doesNotExist()).andExpect(jsonPath("$.data.transactions.size").value(10))
                .andExpect(jsonPath("$.data.transactions.totalElements").value(2));
        graph("query($id: ID!) { transactions(accountId: $id, page: 1, size: 1) { page totalPages content { id } } }", Map.of("id", accountId.toString()))
                .andExpect(jsonPath("$.errors").doesNotExist()).andExpect(jsonPath("$.data.transactions.totalPages").value(2))
                .andExpect(jsonPath("$.data.transactions.content.length()").value(1));
        graph("query($id: ID!) { transactions(accountId: $id) { totalElements } }", Map.of("id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.data.transactions.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void idempotencyConflictsAreErrorsInsideHttp200(boolean isTransfer) throws Exception {
        var input = isTransfer ? transfer() : debit();
        String mutation = isTransfer ? TRANSFER : CREATE;
        graph(mutation, Map.of("input", input, "key", "conflict")).andExpect(jsonPath("$.errors").doesNotExist());
        input.put("amount", "251");
        graph(mutation, Map.of("input", input, "key", "conflict"))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(SERVER.operationCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "currency", "uuid", "number", "blank-key", "exceeded-ceiling"})
    void invalidInputsAreStructuredAndNeverCallAccount(String invalid) throws Exception {
        var input = debit(); String key = "invalid";
        switch (invalid) {
            case "zero" -> input.put("amount", "0");
            case "currency" -> input.put("currency", "mad");
            case "uuid" -> input.put("accountId", "invalid");
            case "number" -> input.put("amount", 250.0);
            case "blank-key" -> key = " ";
            case "exceeded-ceiling" -> input.put("amount", "1000.01");
        }
        graph(CREATE, Map.of("input", input, "key", key)).andExpect(jsonPath("$.errors[0].extensions.code")
                .value(invalid.equals("exceeded-ceiling") ? "MAX_AMOUNT_EXCEEDED" : "INVALID_INPUT"));
        assertThat(repository.count()).isZero(); assertThat(SERVER.requests()).isEmpty();
    }

    @Test void rejectedOperationHasBusinessErrorAndRemainsQueryableAsRejected() throws Exception {
        SERVER.respond(200, "BLOCKED", "MAD", "1500");
        graph(CREATE, Map.of("input", debit(), "key", "blocked"))
                .andExpect(jsonPath("$.data.createTransaction").isEmpty())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("ACCOUNT_BLOCKED"));
        var stored = repository.findAll().get(0);
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.REJECTED);
        graph("query($id: ID!) { transaction(id: $id) { status rejectionCode } }", Map.of("id", stored.getId().toString()))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.transaction.status").value("REJECTED"));
        assertThat(SERVER.balance()).isEqualByComparingTo("1500");
    }

    @Test void missingTransactionAndInvalidPaginationReturnGraphQlErrors() throws Exception {
        graph("query($id: ID!) { transaction(id: $id) { id } }", Map.of("id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("TRANSACTION_NOT_FOUND"));
        graph("query($id: ID!) { transactions(accountId: $id, page: -1, size: 101) { totalElements } }", Map.of("id", accountId.toString()))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_INPUT"));
    }

    @Test void accountOutagePreservesPendingAndRecoversSameOperation() throws Exception {
        SERVER.respond(503, "ACTIVE", "MAD", "1500");
        var vars = Map.<String,Object>of("input", transfer(), "key", "unavailable");
        String id = id(graph(TRANSFER, vars).andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createTransfer.status").value("PENDING")), "createTransfer");
        SERVER.available(); service.retryPending();
        graph(TRANSFER, vars).andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createTransfer.id").value(id))
                .andExpect(jsonPath("$.data.createTransfer.status").value("COMPLETED"));
        assertThat(SERVER.operationCount()).isEqualTo(1);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
    }
}
