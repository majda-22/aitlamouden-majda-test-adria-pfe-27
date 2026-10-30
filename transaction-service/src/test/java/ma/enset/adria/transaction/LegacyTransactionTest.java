package ma.enset.adria.transaction;

import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class LegacyTransactionTest {
    @TempDir Path directory;

    @Test void upgradesOldSchemaAndNeverDebitsHistoricalRowsEvenAfterRestart() throws Exception {
        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        String url = "jdbc:h2:file:" + directory.resolve("legacy") + ";DB_CLOSE_ON_EXIT=FALSE";
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("create table bank_transactions (id uuid primary key, account_id uuid not null, "
                    + "amount numeric(38,18) not null, currency varchar(3) not null, merchant_category varchar(255) not null, "
                    + "country varchar(255) not null, created_at timestamp(6) with time zone not null)");
            statement.execute("insert into bank_transactions values ('" + id + "','" + account
                    + "',250,'MAD','SHOP','MA',CURRENT_TIMESTAMP)");
        }
        try (var server = new AccountStub()) {
            for (int restart = 0; restart < 2; restart++) {
                try (var context = new SpringApplicationBuilder(TransactionServiceApplication.class).web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=" + url, "--bank.account-service.url=" + server.url(),
                                "--bank.transaction.recovery.enabled=false")) {
                    var service = context.getBean(TransactionService.class);
                    assertThat(service.get(id).status()).isEqualTo(TransactionStatus.LEGACY_UNDEBITED);
                    assertThat(service.list(account, 0, 10).totalElements()).isEqualTo(1);
                    service.retryPending();
                    assertThat(server.requests()).isEmpty();
                }
            }
        }
    }
}
