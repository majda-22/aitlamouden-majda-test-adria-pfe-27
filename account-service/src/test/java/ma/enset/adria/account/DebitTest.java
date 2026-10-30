package ma.enset.adria.account;

import ma.enset.adria.account.repository.DebitOperationRepository;
import ma.enset.adria.account.service.*;
import ma.enset.adria.account.service.dto.*;
import ma.enset.adria.account.domain.AccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:debit-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DebitTest {
    @Autowired AccountService accounts;
    @Autowired DebitService debits;
    @Autowired DebitOperationRepository operations;
    @Autowired MockMvc mvc;

    private UUID account(String balance) {
        return accounts.create(new CreateAccountRequest("DEBIT-TEST", "MAD", new BigDecimal(balance))).id();
    }
    private DebitRequest request(UUID operation, UUID account, String amount) {
        return new DebitRequest(operation, account, new BigDecimal(amount), "MAD");
    }

    @Test void debits250From1500AndReplaysTheOriginalResponse() {
        var id = account("1500");
        var request = request(UUID.randomUUID(), id, "250");
        var first = debits.debit(request);
        debits.debit(request(UUID.randomUUID(), id, "100"));
        var replay = debits.debit(request(request.operationId(), id, "250.00"));
        assertThat(first.balanceAfter()).isEqualByComparingTo("1250");
        assertThat(replay.balanceAfter()).isEqualByComparingTo("1250");
        assertThat(accounts.get(id).balance()).isEqualByComparingTo("1150");
    }

    @Test void insufficientBalanceIsNotDebitedAndRefusalIsRemembered() {
        var id = account("100");
        var request = request(UUID.randomUUID(), id, "250");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> debits.debit(request)).isInstanceOfSatisfying(DebitException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_BALANCE"));
        }
        assertThat(accounts.get(id).balance()).isEqualByComparingTo("100");
        assertThat(operations.findById(request.operationId())).isPresent();
    }

    @Test void conflictingParametersNeverDebitAgain() {
        var id = account("1500");
        var op = UUID.randomUUID();
        debits.debit(request(op, id, "250"));
        assertThatThrownBy(() -> debits.debit(request(op, id, "251")))
                .isInstanceOfSatisfying(DebitException.class, ex -> assertThat(ex.getCode()).isEqualTo("OPERATION_CONFLICT"));
        assertThatThrownBy(() -> debits.debit(request(op, account("1500"), "250")))
                .isInstanceOf(DebitException.class);
        assertThat(accounts.get(id).balance()).isEqualByComparingTo("1250");
    }

    @Test void concurrentDifferentOperationsCannotOverdrawAccount() throws Exception {
        var id = account("1000");
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                start.await();
                try { debits.debit(request(UUID.randomUUID(), id, "250")); return true; }
                catch (DebitException ex) {
                    assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_BALANCE"); return false;
                }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(4);
            assertThat(accounts.get(id).balance()).isEqualByComparingTo("0");
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentSameOperationDebitsExactlyOnce() throws Exception {
        var id = account("1500");
        var request = request(UUID.randomUUID(), id, "250");
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                start.await(); return debits.debit(request);
            })).toList();
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS).balanceAfter()).isEqualByComparingTo("1250");
            assertThat(accounts.get(id).balance()).isEqualByComparingTo("1250");
        } finally { pool.shutdownNow(); }
    }

    @Test void validatesStatusCurrencyAndMissingAccount() {
        var id = account("1500");
        accounts.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.BLOCKED));
        assertThatThrownBy(() -> debits.debit(request(UUID.randomUUID(), id, "250")))
                .isInstanceOfSatisfying(DebitException.class, ex -> assertThat(ex.getCode()).isEqualTo("ACCOUNT_BLOCKED"));
        accounts.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.ACTIVE));
        assertThatThrownBy(() -> debits.debit(new DebitRequest(UUID.randomUUID(), id, BigDecimal.ONE, "EUR")))
                .isInstanceOfSatisfying(DebitException.class, ex -> assertThat(ex.getCode()).isEqualTo("CURRENCY_MISMATCH"));
        assertThatThrownBy(() -> debits.debit(request(UUID.randomUUID(), UUID.randomUUID(), "1")))
                .isInstanceOfSatisfying(DebitException.class, ex -> assertThat(ex.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThat(accounts.get(id).balance()).isEqualByComparingTo("1500");
    }

    @Test void httpReturnsSuccessValidationAndConflict() throws Exception {
        var id = account("1500");
        var op = UUID.randomUUID();
        String body = "{\"operationId\":\"" + op + "\",\"accountId\":\"" + id + "\",\"amount\":250,\"currency\":\"MAD\"}";
        mvc.perform(post("/internal/debits").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balanceAfter").value(1250));
        mvc.perform(post("/internal/debits").contentType("application/json").content(body.replace("\"amount\":250", "\"amount\":251")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_CONFLICT"));
        mvc.perform(post("/internal/debits").contentType("application/json").content(body.replace("\"amount\":250", "\"amount\":0")))
                .andExpect(status().isBadRequest());
    }
}
