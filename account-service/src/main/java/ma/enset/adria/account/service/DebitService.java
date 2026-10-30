package ma.enset.adria.account.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import ma.enset.adria.account.domain.AccountStatus;
import ma.enset.adria.account.domain.DebitOperation;
import ma.enset.adria.account.repository.AccountRepository;
import ma.enset.adria.account.repository.DebitOperationRepository;
import ma.enset.adria.account.service.dto.DebitRequest;
import ma.enset.adria.account.service.dto.DebitResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class DebitService {
    private final AccountRepository accounts;
    private final DebitOperationRepository operations;
    private final TransactionTemplate sql;

    public DebitService(AccountRepository accounts, DebitOperationRepository operations, PlatformTransactionManager manager) {
        this.accounts = accounts;
        this.operations = operations;
        sql = new TransactionTemplate(manager);
    }

    public DebitResponse debit(@NotNull @Valid DebitRequest request) {
        DebitResponse result;
        try {
            result = sql.execute(tx -> {
                var previous = operations.findById(request.operationId());
                if (previous.isPresent()) return replay(previous.get(), request);
                // La clé primaire réserve operationId. Un appel concurrent attend le commit.
                var operation = operations.saveAndFlush(new DebitOperation(request));
                var account = accounts.findLockedById(request.accountId()).orElse(null);
                String refusal = null;
                if (account == null) refusal = "ACCOUNT_NOT_FOUND";
                else if (account.getStatus() != AccountStatus.ACTIVE) refusal = "ACCOUNT_BLOCKED";
                else if (!account.getCurrency().equals(request.currency())) refusal = "CURRENCY_MISMATCH";
                else if (account.getBalance().compareTo(request.amount()) < 0) refusal = "INSUFFICIENT_BALANCE";
                if (refusal == null) account.debit(request.amount());
                operation.finish(account == null ? null : account.getBalance(), refusal);
                return operation.response();
            });
        } catch (DataIntegrityViolationException collision) {
            // Relire après le rollback de la tentative concurrente, dans une nouvelle transaction.
            result = sql.execute(tx -> replay(operations.findById(request.operationId())
                    .orElseThrow(() -> collision), request));
        }
        // Le refus est signalé après commit pour conserver sa réponse idempotente.
        if (result.rejectionCode() != null) throw new DebitException(request.operationId(), result.rejectionCode());
        return result;
    }

    private DebitResponse replay(DebitOperation operation, DebitRequest request) {
        if (!operation.matches(request)) throw new DebitException(request.operationId(), "OPERATION_CONFLICT");
        return operation.response();
    }
}
