package ma.enset.adria.transaction.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "bank.transaction.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PendingTransactionRecovery {
    private final TransactionService service;
    public PendingTransactionRecovery(TransactionService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${bank.transaction.recovery.delay-ms:5000}",
               initialDelayString = "${bank.transaction.recovery.delay-ms:5000}")
    public void recover() { service.retryPending(); }
}
