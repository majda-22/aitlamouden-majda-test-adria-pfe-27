package ma.enset.adria.transaction;

import ma.enset.adria.transaction.service.TransactionException;
import ma.enset.adria.transaction.service.TransactionService;
import ma.enset.adria.transaction.service.dto.CreateTransactionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionPersistenceTest {

    @TempDir
    Path directory;

    @Test
    void preservesTransactionOnRestartAndUsesInjectedCeiling() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId;
        try (var accountServer = new AccountStub()) {
            try (var context = startContext(accountServer.url())) {
                var service = context.getBean(TransactionService.class);
                assertThatThrownBy(() -> service.create("persist-key", request(accountId, "151")))
                        .isInstanceOfSatisfying(TransactionException.class,
                                exception -> assertThat(exception.getCode())
                                        .isEqualTo(TransactionException.Code.MAX_AMOUNT_EXCEEDED));
                assertThat(accountServer.requests()).isEmpty();
                transactionId = service.create("persist-key", request(accountId, "149.123456789")).id();
            }

            assertThat(directory.resolve("transactions.mv.db")).exists();

            try (var context = startContext(accountServer.url())) {
                var service = context.getBean(TransactionService.class);
                var transaction = service.get(transactionId);
                assertThat(transaction.accountId()).isEqualTo(accountId);
                assertThat(transaction.amount()).isEqualByComparingTo("149.123456789");
                assertThat(transaction.currency()).isEqualTo("MAD");
                assertThat(transaction.merchantCategory()).isEqualTo("SHOP");
                assertThat(transaction.country()).isEqualTo("MA");
                assertThat(transaction.createdAt()).isNotNull();
                assertThat(service.list(accountId, 0, 10).totalElements()).isEqualTo(1);
            }
            assertThat(accountServer.requests()).containsExactly(
                    new AccountStub.Request("POST", "/internal/debits"));
        }
    }

    private CreateTransactionRequest request(UUID accountId, String amount) {
        return new CreateTransactionRequest(accountId, new BigDecimal(amount), "MAD", "SHOP", "MA");
    }

    @Test
    void pendingTransferRetainsBothAccountsAndRecoversAfterRestartWithoutSecondMovement() {
        var request = new ma.enset.adria.transaction.service.dto.CreateTransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), "MAD");
        UUID id;
        try (var accountServer = new AccountStub()) {
            accountServer.delay(1200);
            try (var context = startContext(accountServer.url())) {
                var pending = context.getBean(TransactionService.class).createTransfer("persist-transfer", request);
                assertThat(pending.status().name()).isEqualTo("PENDING");
                id = pending.id();
            }
            accountServer.available();
            try (var context = startContext(accountServer.url())) {
                var service = context.getBean(TransactionService.class);
                var restored = service.getTransfer(id);
                assertThat(restored.sourceAccountId()).isEqualTo(request.sourceAccountId());
                assertThat(restored.destinationAccountId()).isEqualTo(request.destinationAccountId());
                service.retryPending();
                assertThat(service.getTransfer(id).status().name()).isEqualTo("COMPLETED");
                assertThat(service.createTransfer("persist-transfer", request).id()).isEqualTo(id);
            }
            assertThat(accountServer.balance()).isEqualByComparingTo("1900");
            assertThat(accountServer.destinationBalance()).isEqualByComparingTo("300");
            assertThat(accountServer.operationCount()).isEqualTo(1);
        }
    }

    private ConfigurableApplicationContext startContext(String accountUrl) {
        return new SpringApplicationBuilder(TransactionServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:h2:file:" + directory.resolve("transactions") + ";DB_CLOSE_ON_EXIT=FALSE",
                        "--bank.account-service.url=" + accountUrl,
                        "--bank.transaction.max-amount=150", "--bank.transaction.recovery.enabled=false",
                        "--spring.cloud.openfeign.client.config.account-service.readTimeout=200", "--spring.main.banner-mode=off");
    }
}
