package ma.enset.adria.account;

import ma.enset.adria.account.domain.AccountStatus;
import ma.enset.adria.account.service.AccountService;
import ma.enset.adria.account.service.dto.ChangeAccountStatusRequest;
import ma.enset.adria.account.service.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPersistenceTest {

    @TempDir
    Path directory;

    @Test
    void preservesAccountAndStatusAfterClosingAndReopeningFileDatabase() {
        UUID id;
        try (var context = startContext()) {
            var service = context.getBean(AccountService.class);
            id = service.create(new CreateAccountRequest("CLIENT-PERSIST", "EUR",
                    new BigDecimal("123.456789"))).id();
            service.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.BLOCKED));
        }

        assertThat(directory.resolve("accounts.mv.db")).exists();

        try (var context = startContext()) {
            var account = context.getBean(AccountService.class).get(id);
            assertThat(account.customerReference()).isEqualTo("CLIENT-PERSIST");
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.status()).isEqualTo(AccountStatus.BLOCKED);
            assertThat(account.balance()).isEqualByComparingTo("123.456789");
        }
    }

    private ConfigurableApplicationContext startContext() {
        return new SpringApplicationBuilder(AccountServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:h2:file:" + directory.resolve("accounts") + ";DB_CLOSE_ON_EXIT=FALSE",
                        "--spring.jpa.hibernate.ddl-auto=update", "--spring.main.banner-mode=off");
    }

    @Test
    void debitIdempotencySurvivesRestart() {
        UUID accountId;
        UUID operationId = UUID.randomUUID();
        try (var context = startContext()) {
            accountId = context.getBean(AccountService.class).create(new CreateAccountRequest(
                    "PERSIST-DEBIT", "MAD", new BigDecimal("1500"))).id();
            context.getBean(ma.enset.adria.account.service.DebitService.class).debit(
                    new ma.enset.adria.account.service.dto.DebitRequest(operationId, accountId, new BigDecimal("250"), "MAD"));
        }
        try (var context = startContext()) {
            var replay = context.getBean(ma.enset.adria.account.service.DebitService.class).debit(
                    new ma.enset.adria.account.service.dto.DebitRequest(operationId, accountId, new BigDecimal("250.00"), "MAD"));
            assertThat(replay.balanceAfter()).isEqualByComparingTo("1250");
            assertThat(context.getBean(AccountService.class).get(accountId).balance()).isEqualByComparingTo("1250");
        }
    }
}
