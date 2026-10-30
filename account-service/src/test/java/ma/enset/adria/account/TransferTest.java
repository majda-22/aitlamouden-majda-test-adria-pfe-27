package ma.enset.adria.account;

import ma.enset.adria.account.domain.AccountStatus;
import ma.enset.adria.account.repository.DebitOperationRepository;
import ma.enset.adria.account.service.*;
import ma.enset.adria.account.service.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:account-transfer-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class TransferTest {
    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired DebitService debits;
    @Autowired DebitOperationRepository operations;
    @Autowired MockMvc mvc;

    UUID account(String balance, String currency) {
        return accounts.create(new CreateAccountRequest("TRANSFER-TEST", currency, new BigDecimal(balance))).id();
    }
    TransferRequest request(UUID source, UUID destination, String amount) {
        return new TransferRequest(UUID.randomUUID(), source, destination, new BigDecimal(amount), "MAD");
    }
    void balances(UUID source, String a, UUID destination, String b) {
        assertThat(accounts.get(source).balance()).isEqualByComparingTo(a);
        assertThat(accounts.get(destination).balance()).isEqualByComparingTo(b);
    }
    void refused(TransferRequest request, String code) {
        assertThatThrownBy(() -> transfers.transfer(request)).isInstanceOfSatisfying(DebitException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test void transfers250AndReplaysWithoutSecondMovement() {
        var source = account("1500", "MAD"); var destination = account("200", "MAD");
        var request = request(source, destination, "250");
        var first = transfers.transfer(request);
        var replay = transfers.transfer(new TransferRequest(request.operationId(), source, destination, new BigDecimal("250.00"), "MAD"));
        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(replay.sourceBalanceAfter()).isEqualByComparingTo("1250");
        assertThat(replay.destinationBalanceAfter()).isEqualByComparingTo("450");
        balances(source, "1250", destination, "450");
        refused(new TransferRequest(request.operationId(), source, destination, new BigDecimal("251"), "MAD"), "OPERATION_CONFLICT");
        refused(new TransferRequest(request.operationId(), destination, source, new BigDecimal("250"), "MAD"), "OPERATION_CONFLICT");
        balances(source, "1250", destination, "450");
    }

    @Test void refusesSameAccountAndRemembersRefusal() {
        var source = account("1500", "MAD"); var request = request(source, source, "250");
        refused(request, "SAME_ACCOUNT"); refused(request, "SAME_ACCOUNT");
        assertThat(accounts.get(source).balance()).isEqualByComparingTo("1500");
    }

    @Test void refusesMissingSourceOrDestination() {
        var source = account("1500", "MAD"); var destination = account("200", "MAD");
        refused(request(UUID.randomUUID(), destination, "250"), "ACCOUNT_NOT_FOUND");
        refused(request(source, UUID.randomUUID(), "250"), "ACCOUNT_NOT_FOUND");
        balances(source, "1500", destination, "200");
    }

    @Test void refusesBlockedSourceOrDestination() {
        var source = account("1500", "MAD"); var destination = account("200", "MAD");
        for (UUID id : List.of(source, destination)) {
            accounts.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.BLOCKED));
            refused(request(source, destination, "250"), "ACCOUNT_BLOCKED");
            accounts.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.ACTIVE));
        }
        balances(source, "1500", destination, "200");
    }

    @Test void refusesEitherCurrencyMismatchAndInsufficientBalance() {
        var mad = account("1500", "MAD"); var eur = account("200", "EUR");
        refused(request(mad, eur, "250"), "CURRENCY_MISMATCH");
        refused(request(eur, mad, "100"), "CURRENCY_MISMATCH");
        var small = account("100", "MAD");
        refused(request(small, mad, "250"), "INSUFFICIENT_BALANCE");
        balances(mad, "1500", small, "100");
    }

    @Test void databaseFailureDuringMovementRollsBackBothBalancesAndOperation() {
        var source = account("1500", "MAD");
        var destination = account("99999999999999999999", "MAD");
        var request = request(source, destination, "250");
        // Le crédit dépasse numeric(38,18) : l'échec SQL doit annuler aussi le débit.
        assertThatThrownBy(() -> transfers.transfer(request)).isInstanceOf(DataIntegrityViolationException.class);
        balances(source, "1500", destination, "99999999999999999999");
        assertThat(operations.findById(request.operationId())).isEmpty();
    }

    @Test void sharedOperationIdCannotBeReusedBetweenDebitAndTransfer() {
        var source = account("1500", "MAD"); var destination = account("200", "MAD");
        var request = request(source, destination, "250");
        transfers.transfer(request);
        assertThatThrownBy(() -> debits.debit(new DebitRequest(request.operationId(), source, new BigDecimal("250"), "MAD")))
                .isInstanceOfSatisfying(DebitException.class, ex -> assertThat(ex.getCode()).isEqualTo("OPERATION_CONFLICT"));
        balances(source, "1250", destination, "450");
    }

    @Test void concurrentReplaysMoveBothBalancesOnce() throws Exception {
        var source = account("1500", "MAD"); var destination = account("200", "MAD");
        var request = request(source, destination, "250");
        var pool = Executors.newFixedThreadPool(6); var start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                start.await(); return transfers.transfer(request);
            })).toList();
            start.countDown();
            for (var f : futures) assertThat(f.get(20, TimeUnit.SECONDS).operationId()).isEqualTo(request.operationId());
            balances(source, "1250", destination, "450");
        } finally { pool.shutdownNow(); }
    }

    @Test void oppositeConcurrentTransfersFinishWithoutDeadlock() throws Exception {
        var a = account("1000", "MAD"); var b = account("1000", "MAD");
        var pool = Executors.newFixedThreadPool(6); var start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 12).mapToObj(i -> pool.submit(() -> {
                start.await(); return transfers.transfer(request(i % 2 == 0 ? a : b, i % 2 == 0 ? b : a, "10"));
            })).toList();
            start.countDown();
            for (var f : futures) assertThat(f.get(20, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            balances(a, "1000", b, "1000");
        } finally { pool.shutdownNow(); }
    }

    @Test void httpValidatesAmountAndExposesConfirmedResult() throws Exception {
        var source = account("1500", "MAD"); var destination = account("200", "MAD");
        String body = "{\"operationId\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\"" + source
                + "\",\"destinationAccountId\":\"" + destination + "\",\"amount\":250,\"currency\":\"MAD\"}";
        mvc.perform(post("/internal/transfers").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceBalanceAfter").value(1250))
                .andExpect(jsonPath("$.destinationBalanceAfter").value(450));
        for (String amount : List.of("0", "-1")) mvc.perform(post("/internal/transfers").contentType("application/json")
                .content(body.replace("\"amount\":250", "\"amount\":" + amount))).andExpect(status().isBadRequest());
        balances(source, "1250", destination, "450");
    }

    @Test void replayUsesStoredTransferBeforeChangedBalancesAndBlockedAccounts() {
        var source = account("1500", "MAD"); var destination = account("200", "MAD");
        var original = request(source, destination, "1000");
        transfers.transfer(original);
        debits.debit(new DebitRequest(UUID.randomUUID(), source, new BigDecimal("400"), "MAD"));
        accounts.changeStatus(source, new ChangeAccountStatusRequest(AccountStatus.BLOCKED));
        accounts.changeStatus(destination, new ChangeAccountStatusRequest(AccountStatus.BLOCKED));
        var replay = transfers.transfer(original);
        assertThat(replay.status()).isEqualTo("COMPLETED");
        assertThat(replay.sourceBalanceAfter()).isEqualByComparingTo("500");
        assertThat(replay.destinationBalanceAfter()).isEqualByComparingTo("1200");
        balances(source, "100", destination, "1200");
    }
}
