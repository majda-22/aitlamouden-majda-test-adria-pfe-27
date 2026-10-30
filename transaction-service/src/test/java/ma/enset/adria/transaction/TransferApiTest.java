package ma.enset.adria.transaction;

import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.repository.TransactionRepository;
import ma.enset.adria.transaction.service.*;
import ma.enset.adria.transaction.service.dto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:transfer-api-test;DB_CLOSE_DELAY=-1",
        "bank.transaction.recovery.enabled=false", "spring.cloud.openfeign.client.config.account-service.readTimeout=200"})
@AutoConfigureMockMvc
@Import(TransactionRecoveryTest.HttpBoundaryCheck.class)
class TransferApiTest {
    static final AccountStub SERVER = new AccountStub();
    @DynamicPropertySource static void url(DynamicPropertyRegistry registry) { registry.add("bank.account-service.url", SERVER::url); }
    @AfterAll static void stop() { SERVER.close(); }
    @Autowired TransactionService service;
    @Autowired TransactionRepository repository;
    @Autowired MockMvc mvc;
    UUID source;
    UUID destination;
    @Autowired io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuits;

    @BeforeEach void reset() {
        circuits.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
        repository.deleteAll(); SERVER.reset(); SERVER.respond(200, "ACTIVE", "MAD", "1500");
        source = UUID.randomUUID(); destination = UUID.randomUUID();
    }
    CreateTransferRequest request(String amount) {
        return new CreateTransferRequest(source, destination, new BigDecimal(amount), "MAD");
    }
    String body(String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
                + "\",\"amount\":" + amount + ",\"currency\":\"MAD\"}";
    }

    @Test void createsConsultsAndReplaysOneTransfer() throws Exception {
        var first = mvc.perform(post("/api/transfers").header("Idempotency-Key", "same-transfer")
                        .contentType("application/json").content(body("250")))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sourceAccountId").value(source.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(destination.toString()))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(first, "$.id");
        mvc.perform(get("/api/transfers/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/transactions/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/transactions").param("accountId", source.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "same-transfer")
                        .contentType("application/json").content(body("250.00")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(SERVER.requests()).containsExactly(new AccountStub.Request("POST", "/internal/transfers"));
    }

    @Test void changedAmountOrDestinationOrOperationTypeConflicts() throws Exception {
        service.createTransfer("key", request("250"));
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "key")
                        .contentType("application/json").content(body("251")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> service.createTransfer("key", new CreateTransferRequest(source, UUID.randomUUID(), new BigDecimal("250"), "MAD")))
                .isInstanceOfSatisfying(TransactionException.class, ex -> assertThat(ex.getCode()).isEqualTo(TransactionException.Code.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> service.create("key", new CreateTransactionRequest(source, new BigDecimal("250"), "MAD", "TRANSFER", "N/A")))
                .isInstanceOfSatisfying(TransactionException.class, ex -> assertThat(ex.getCode()).isEqualTo(TransactionException.Code.IDEMPOTENCY_CONFLICT));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null", "\"abc\""})
    void validatesAmountBeforePersistence(String amount) throws Exception {
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "invalid")
                        .contentType("application/json").content(body(amount)))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero(); assertThat(SERVER.requests()).isEmpty();
    }

    @Test void requiresKeyAndBothAccounts() throws Exception {
        mvc.perform(post("/api/transfers").contentType("application/json").content(body("250")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers").header("Idempotency-Key", " ")
                        .contentType("application/json").content(body("250"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "invalid")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test void appliesConfiguredCeiling() throws Exception {
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "ceiling")
                        .contentType("application/json").content(body("1000.01")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAX_AMOUNT_EXCEEDED"));
        assertThat(repository.count()).isZero(); assertThat(SERVER.requests()).isEmpty();
        assertThat(service.createTransfer("exact-ceiling", request("1000")).status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAME_ACCOUNT", "ACCOUNT_NOT_FOUND", "ACCOUNT_BLOCKED", "CURRENCY_MISMATCH", "INSUFFICIENT_BALANCE"})
    void recordsConfirmedRefusalsWithoutMovement(String refusal) throws Exception {
        switch (refusal) {
            case "SAME_ACCOUNT" -> destination = source;
            case "ACCOUNT_NOT_FOUND" -> SERVER.respond(404, "ACTIVE", "MAD", "1500");
            case "ACCOUNT_BLOCKED" -> SERVER.respond(200, "BLOCKED", "MAD", "1500");
            case "CURRENCY_MISMATCH" -> SERVER.respond(200, "ACTIVE", "EUR", "1500");
            case "INSUFFICIENT_BALANCE" -> SERVER.respond(200, "ACTIVE", "MAD", "100");
        }
        BigDecimal before = SERVER.balance();
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "refusal")
                        .contentType("application/json").content(body("250")))
                .andExpect(status().is(refusal.equals("ACCOUNT_NOT_FOUND") ? 404 : 400))
                .andExpect(jsonPath("$.code").value(refusal));
        var stored = repository.findAll().get(0);
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.REJECTED);
        service.retryPending();
        assertThat(SERVER.requests()).hasSize(1);
        assertThat(SERVER.balance()).isEqualByComparingTo(before);
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("200");
    }

    @Test void responseLostAfterMovementRecoversWithoutSecondMovement() {
        SERVER.delay(1200);
        var first = service.createTransfer("lost-transfer", request("250"));
        assertThat(first.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
        SERVER.available(); service.retryPending();
        var recovered = service.getTransfer(first.id());
        assertThat(recovered.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(service.createTransfer("lost-transfer", request("250")).id()).isEqualTo(first.id());
        assertThat(SERVER.operationCount()).isEqualTo(1);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
        assertThat(SERVER.requests()).containsExactly(new AccountStub.Request("POST", "/internal/transfers"),
                new AccountStub.Request("POST", "/internal/transfers"));
    }

    @Test void temporaryFailureStaysPendingAndRecovers() throws Exception {
        SERVER.respond(503, "ACTIVE", "MAD", "1500");
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "pending")
                        .contentType("application/json").content(body("250")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
        SERVER.available(); service.retryPending();
        assertThat(repository.findAll().get(0).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
    }

    @Test void consultationDoesNotConfuseDebitsWithTransfers() throws Exception {
        var debit = service.create("debit", new CreateTransactionRequest(source, BigDecimal.ONE, "MAD", "SHOP", "MA"));
        mvc.perform(get("/api/transfers/{id}", debit.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/transfers/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/transfers/invalid")).andExpect(status().isBadRequest());
    }
}
