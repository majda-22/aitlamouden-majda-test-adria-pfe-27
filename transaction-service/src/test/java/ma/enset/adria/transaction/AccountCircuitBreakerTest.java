package ma.enset.adria.transaction;

import io.github.resilience4j.circuitbreaker.*;
import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.repository.TransactionRepository;
import ma.enset.adria.transaction.service.TransactionService;
import ma.enset.adria.transaction.service.client.AccountClient;
import ma.enset.adria.transaction.service.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.test.context.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:circuit-test;DB_CLOSE_DELAY=-1",
        "bank.transaction.recovery.enabled=false", "spring.cloud.openfeign.client.config.account-service.readTimeout=200",
        "resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=200ms"})
@AutoConfigureMockMvc
@Import(AccountCircuitBreakerTest.ReadProbe.class)
class AccountCircuitBreakerTest {
    static final AccountStub SERVER = new AccountStub();
    @DynamicPropertySource static void url(DynamicPropertyRegistry r) { r.add("bank.account-service.url", SERVER::url); }
    @AfterAll static void stop() { SERVER.close(); }
    @Autowired TransactionService service;
    @Autowired TransactionRepository repository;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired AccountClient client;
    @Autowired MockMvc mvc;
    CircuitBreaker circuit;
    final List<CircuitBreaker.StateTransition> transitions = new CopyOnWriteArrayList<>();

    // Sonde uniquement dans les tests : vérifie le contrat HTTP d'une lecture distante bloquée.
    @RestController
    static class ReadProbe {
        private final Resilience4JCircuitBreakerFactory factory;
        ReadProbe(Resilience4JCircuitBreakerFactory factory) { this.factory = factory; }
        @GetMapping("/__test/remote-read") String read() {
            return factory.create("accountService").run(() -> "unused-while-open");
        }
    }

    @BeforeEach void reset() {
        repository.deleteAll(); SERVER.reset();
        circuit = registry.circuitBreaker("accountService"); circuit.reset();
        circuit.getEventPublisher().onStateTransition(e -> transitions.add(e.getStateTransition()));
    }

    CreateTransactionRequest debit() {
        return new CreateTransactionRequest(UUID.randomUUID(), new BigDecimal("250"), "MAD", "SHOP", "MA");
    }
    void openWithServerFailures() {
        SERVER.respond(503, "ACTIVE", "MAD", "2000");
        for (int i = 0; i < 4; i++) assertThat(service.create("failure-" + i, debit()).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(SERVER.requests()).hasSize(4);
    }

    @Test void technicalFailuresOpenSharedCircuitAndBlockHttpWhilePreservingPending() throws Exception {
        openWithServerFailures();
        assertThat(service.create("open-debit", debit()).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(service.createTransfer("open-transfer", new CreateTransferRequest(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("250"), "MAD")).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(SERVER.requests()).hasSize(4);
        assertThat(repository.count()).isEqualTo(6);
        mvc.perform(get("/__test/remote-read")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));
        assertThat(SERVER.requests()).hasSize(4);
    }

    @Test void businessRefusalsDoNotCountAsFailuresOrOpenCircuit() {
        SERVER.respond(200, "BLOCKED", "MAD", "2000");
        for (int i = 0; i < 8; i++) assertThat(service.create("blocked-" + i, debit()).status()).isEqualTo(TransactionStatus.REJECTED);
        SERVER.respond(404, "ACTIVE", "MAD", "2000");
        for (int i = 0; i < 4; i++) assertThat(service.create("missing-" + i, debit()).status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(SERVER.requests()).hasSize(12);
    }

    @Test void restoredServiceTransitionsThroughHalfOpenAndClosed() throws Exception {
        openWithServerFailures(); SERVER.available();
        Thread.sleep(250); // Attente supérieure aux 200 ms configurées uniquement pour ce test.
        service.retryPending();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(transitions).contains(CircuitBreaker.StateTransition.CLOSED_TO_OPEN,
                CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN, CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED);
        assertThat(repository.findAll()).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TransactionStatus.COMPLETED));
        assertThat(SERVER.requests()).hasSize(8);
        assertThat(SERVER.balance()).isEqualByComparingTo("1000");
    }

    @Test void networkConnectionDropIsOneAttemptWithoutTransportOrFeignRetry() {
        SERVER.dropConnection();
        assertThat(service.create("connection-drop", debit()).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(SERVER.requests()).hasSize(1);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test void repeatedReadTimeoutsOpenCircuit() {
        SERVER.delay(1200);
        for (int i = 0; i < 4; i++) assertThat(service.create("timeout-" + i, debit()).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(SERVER.requests()).hasSize(4);
    }

    @Test void debitAndTransferRecoverOriginalResultsDespiteChangedStatusAndBalance() throws Exception {
        SERVER.respond(200, "ACTIVE", "MAD", "1500"); SERVER.delay(1200);
        var debitRequest = debit();
        var transferRequest = new CreateTransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("250"), "MAD");
        var debit = service.create("lost-debit", debitRequest);
        var transfer = service.createTransfer("lost-transfer", transferRequest);
        assertThat(debit.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(transfer.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(SERVER.balance()).isEqualByComparingTo("1000");
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
        circuit.transitionToOpenState();
        // Un compte désormais bloqué ne doit pas invalider des opérations déjà committées.
        SERVER.respond(200, "BLOCKED", "MAD", "1000");
        Thread.sleep(250);
        service.retryPending();
        assertThat(service.get(debit.id()).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(service.getTransfer(transfer.id()).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(service.create("lost-debit", debitRequest).id()).isEqualTo(debit.id());
        assertThat(service.createTransfer("lost-transfer", transferRequest).id()).isEqualTo(transfer.id());
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(SERVER.balance()).isEqualByComparingTo("1000");
        assertThat(SERVER.destinationBalance()).isEqualByComparingTo("450");
        assertThat(SERVER.operationCount()).isEqualTo(2);
        assertThat(SERVER.requests()).hasSize(4);
    }
}
