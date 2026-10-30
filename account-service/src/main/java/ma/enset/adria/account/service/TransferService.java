package ma.enset.adria.account.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import ma.enset.adria.account.domain.*;
import ma.enset.adria.account.repository.*;
import ma.enset.adria.account.service.dto.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class TransferService {
    private final AccountRepository accounts;
    private final DebitOperationRepository operations;
    private final TransactionTemplate sql;

    public TransferService(AccountRepository accounts, DebitOperationRepository operations, PlatformTransactionManager manager) {
        this.accounts = accounts; this.operations = operations; sql = new TransactionTemplate(manager);
    }

    public TransferResponse transfer(@NotNull @Valid TransferRequest request) {
        TransferResponse result;
        try {
            result = sql.execute(tx -> {
                var existing = operations.findById(request.operationId());
                if (existing.isPresent()) return replay(existing.get(), request);
                var operation = operations.saveAndFlush(new DebitOperation(request));
                if (request.sourceAccountId().equals(request.destinationAccountId())) {
                    operation.finishTransfer(null, null, "SAME_ACCOUNT");
                    return operation.transferResponse();
                }
                // Le même ordre UUID est utilisé même pour deux virements opposés.
                boolean sourceFirst = request.sourceAccountId().compareTo(request.destinationAccountId()) < 0;
                var first = accounts.findLockedById(sourceFirst ? request.sourceAccountId() : request.destinationAccountId()).orElse(null);
                var second = accounts.findLockedById(sourceFirst ? request.destinationAccountId() : request.sourceAccountId()).orElse(null);
                Account source = sourceFirst ? first : second;
                Account destination = sourceFirst ? second : first;
                String refusal = null;
                if (source == null || destination == null) refusal = "ACCOUNT_NOT_FOUND";
                else if (source.getStatus() != AccountStatus.ACTIVE || destination.getStatus() != AccountStatus.ACTIVE) refusal = "ACCOUNT_BLOCKED";
                else if (!request.currency().equals(source.getCurrency()) || !request.currency().equals(destination.getCurrency())) refusal = "CURRENCY_MISMATCH";
                else if (source.getBalance().compareTo(request.amount()) < 0) refusal = "INSUFFICIENT_BALANCE";
                if (refusal == null) {
                    source.debit(request.amount());
                    destination.credit(request.amount());
                }
                operation.finishTransfer(source == null ? null : source.getBalance(),
                        destination == null ? null : destination.getBalance(), refusal);
                return operation.transferResponse();
            });
        } catch (DataIntegrityViolationException collision) {
            result = sql.execute(tx -> replay(operations.findById(request.operationId()).orElseThrow(() -> collision), request));
        }
        if (result.rejectionCode() != null) throw refusal(request, result.rejectionCode());
        return result;
    }

    private TransferResponse replay(DebitOperation operation, TransferRequest request) {
        if (!operation.matches(request)) throw refusal(request, "OPERATION_CONFLICT");
        return operation.transferResponse();
    }

    private DebitException refusal(TransferRequest request, String code) {
        return new DebitException(request.operationId(), code, "Virement refusé : " + code);
    }
}
