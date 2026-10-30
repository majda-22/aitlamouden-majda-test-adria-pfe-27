package ma.enset.adria.transaction;

import feign.RequestInterceptor;
import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.repository.TransactionRepository;
import ma.enset.adria.transaction.service.*;
import ma.enset.adria.transaction.service.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:recovery-test;DB_CLOSE_DELAY=-1",
        "bank.transaction.recovery.enabled=false", "spring.cloud.openfeign.client.config.account-service.readTimeout=200"})
@AutoConfigureMockMvc
@Import(TransactionRecoveryTest.HttpBoundaryCheck.class)
class TransactionRecoveryTest {
    static final AccountStub SERVER = new AccountStub();
    @DynamicPropertySource static void url(DynamicPropertyRegistry r) { r.add("bank.account-service.url", SERVER::url); }
    @AfterAll static void stop() { SERVER.close(); }
    @Autowired TransactionService service;
    @Autowired TransactionRepository repository;
    @Autowired MockMvc mvc;
    @BeforeEach void reset() { repository.deleteAll(); SERVER.reset(); SERVER.respond(200, "ACTIVE", "MAD", "1500"); }

    @TestConfiguration
    static class HttpBoundaryCheck {
        @Bean RequestInterceptor verifySqlCommitted(TransactionRepository repository) {
            return template -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(repository.count()).isGreaterThan(0); // PENDING déjà committé avant Feign.
            };
        }
    }

    private CreateTransactionRequest request(UUID id, String amount) {
        return new CreateTransactionRequest(id, new BigDecimal(amount), "MAD", "SHOP", "MA");
    }

    @Test void debitCommittedButResponseLostThenRecoveryDoesNotDebitAgain() {
        SERVER.delay(1200);
        var request = request(UUID.randomUUID(), "250");
        var first = service.create("lost-response", request);
        assertThat(first.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        SERVER.available();
        service.retryPending();
        var recovered = service.get(first.id());
        assertThat(recovered.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(service.create("lost-response", request).id()).isEqualTo(first.id());
        service.retryPending();
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        assertThat(SERVER.operationCount()).isEqualTo(1);
        assertThat(SERVER.requests()).hasSize(2);
    }

    @Test void temporaryFailureCanBeRetriedWithSameCreationKey() {
        SERVER.respond(503, "ACTIVE", "MAD", "1500");
        var request = request(UUID.randomUUID(), "250");
        var pending = service.create("temporary-failure", request);
        assertThat(pending.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(SERVER.balance()).isEqualByComparingTo("1500");
        SERVER.available();
        var completed = service.create("temporary-failure", request);
        assertThat(completed.id()).isEqualTo(pending.id());
        assertThat(completed.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
    }

    @Test void concurrentCreationWithSameKeyProducesOneTransactionAndDebit() throws Exception {
        var request = request(UUID.randomUUID(), "250");
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                start.await(); return service.create("concurrent-key", request);
            })).toList();
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(SERVER.operationCount()).isEqualTo(1);
            assertThat(SERVER.balance()).isEqualByComparingTo("1250");
        } finally { pool.shutdownNow(); }
    }

    @Test void rejectedTransactionsAreNeverRetried() {
        var rejected = service.create("rejected", request(UUID.randomUUID(), "1000"));
        // Premier débit réussi, puis solde insuffisant pour un second.
        assertThat(rejected.status()).isEqualTo(TransactionStatus.COMPLETED);
        var second = service.create("insufficient", request(UUID.randomUUID(), "1000"));
        assertThat(second.status()).isEqualTo(TransactionStatus.REJECTED);
        int requests = SERVER.requests().size();
        service.retryPending();
        assertThat(SERVER.requests()).hasSize(requests);
        assertThat(SERVER.balance()).isEqualByComparingTo("500");
    }

    @Test void missingKeyIs400AndDifferentPayloadWithSameKeyIs409() throws Exception {
        String body = "{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":250,\"currency\":\"MAD\","
                + "\"merchantCategory\":\"SHOP\",\"country\":\"MA\"}";
        mvc.perform(post("/api/transactions").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transactions").header("Idempotency-Key", "same-key").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/transactions").header("Idempotency-Key", "same-key").contentType("application/json").content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/transactions").header("Idempotency-Key", "same-key").contentType("application/json")
                        .content(body.replace("\"amount\":250", "\"amount\":251")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(SERVER.balance()).isEqualByComparingTo("1250");
    }
}
