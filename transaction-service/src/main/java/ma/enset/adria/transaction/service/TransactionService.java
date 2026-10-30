package ma.enset.adria.transaction.service;

import feign.FeignException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.service.client.AccountClient;
import ma.enset.adria.transaction.service.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

@Service
@Validated
public class TransactionService {
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final Set<String> REFUSALS = Set.of("ACCOUNT_NOT_FOUND", "ACCOUNT_BLOCKED",
            "CURRENCY_MISMATCH", "INSUFFICIENT_BALANCE", "OPERATION_CONFLICT", "SAME_ACCOUNT");
    private final TransactionStore store;
    private final AccountClient client;
    private final BigDecimal maxAmount;
    private final JsonMapper json;

    public TransactionService(TransactionStore store, AccountClient client, JsonMapper json,
                              @Value("${bank.transaction.max-amount}") BigDecimal maxAmount) {
        this.store = store; this.client = client; this.json = json; this.maxAmount = maxAmount;
        if (maxAmount.signum() <= 0) throw new IllegalArgumentException("Le plafond doit être positif.");
    }

    // L'orchestrateur ne porte pas @Transactional : le commit PENDING précède l'appel Feign.
    public TransactionResponse create(@NotBlank @Size(max = 128) String key,
                                      @NotNull @Valid CreateTransactionRequest request) {
        TransactionResponse transaction;
        try { transaction = store.reserve(key, request, maxAmount); }
        catch (DataIntegrityViolationException collision) { transaction = store.replay(key, request); }
        return attempt(transaction);
    }

    private TransactionResponse attempt(TransactionResponse t) {
        if (t.status() != TransactionStatus.PENDING) return t;
        if (t.destinationAccountId() != null) return attemptTransfer(t);
        DebitResponse result;
        try {
            result = client.debit(new DebitRequest(t.id(), t.accountId(), t.amount(), t.currency()));
        } catch (FeignException exception) {
            String refusal = confirmedRefusal(exception, t.id());
            if (refusal != null) return store.finish(t.id(), TransactionStatus.REJECTED, refusal);
            return store.get(t.id()); // Timeout/5xx/réponse ambiguë : le débit peut déjà être committé.
        }
        if (result == null || !t.id().equals(result.operationId()) || !t.accountId().equals(result.accountId())
                || result.amount() == null || t.amount().compareTo(result.amount()) != 0
                || !t.currency().equals(result.currency()) || !"COMPLETED".equals(result.status())
                || result.balanceAfter() == null || result.balanceAfter().signum() < 0
                || result.rejectionCode() != null) return store.get(t.id());
        return store.finish(t.id(), TransactionStatus.COMPLETED, null);
    }

    public TransferResponse createTransfer(@NotBlank @Size(max = 128) String key,
                                            @NotNull @Valid CreateTransferRequest request) {
        TransactionResponse transaction;
        try { transaction = store.reserveTransfer(key, request, maxAmount); }
        catch (DataIntegrityViolationException collision) { transaction = store.replayTransfer(key, request); }
        return transferResponse(attempt(transaction));
    }

    public TransferResponse getTransfer(@NotNull UUID id) {
        var transaction = store.get(id);
        if (transaction.destinationAccountId() == null)
            throw new TransactionException(TransactionException.Code.TRANSFER_NOT_FOUND, "Virement introuvable : " + id);
        return transferResponse(transaction);
    }

    private TransferResponse transferResponse(TransactionResponse t) {
        return new TransferResponse(t.id(), t.accountId(), t.destinationAccountId(), t.amount(), t.currency(),
                t.createdAt(), t.status(), t.rejectionCode());
    }

    private TransactionResponse attemptTransfer(TransactionResponse t) {
        TransferResult result;
        try {
            result = client.transfer(new TransferRequest(t.id(), t.accountId(), t.destinationAccountId(), t.amount(), t.currency()));
        } catch (FeignException exception) {
            String refusal = confirmedRefusal(exception, t.id());
            if (refusal != null) return store.finish(t.id(), TransactionStatus.REJECTED, refusal);
            return store.get(t.id());
        }
        if (result == null || !t.id().equals(result.operationId()) || !t.accountId().equals(result.sourceAccountId())
                || !t.destinationAccountId().equals(result.destinationAccountId()) || result.amount() == null
                || t.amount().compareTo(result.amount()) != 0 || !t.currency().equals(result.currency())
                || !"COMPLETED".equals(result.status()) || result.rejectionCode() != null
                || result.sourceBalanceAfter() == null || result.sourceBalanceAfter().signum() < 0
                || result.destinationBalanceAfter() == null || result.destinationBalanceAfter().signum() < 0)
            return store.get(t.id());
        return store.finish(t.id(), TransactionStatus.COMPLETED, null);
    }

    private String confirmedRefusal(FeignException exception, UUID operationId) {
        if (exception.status() != 400 && exception.status() != 404 && exception.status() != 409) return null;
        try {
            var body = json.readTree(exception.contentUTF8());
            String code = body.path("code").asText();
            if (operationId.toString().equals(body.path("operationId").asText()) && REFUSALS.contains(code))
                return code;
        } catch (RuntimeException ignored) { /* Réponse non reconnue : conserver PENDING. */ }
        return null;
    }

    public void retryPending() {
        for (UUID id : store.pending()) {
            try { attempt(store.get(id)); }
            catch (RuntimeException exception) { log.warn("Reprise différée de la transaction {}", id, exception); }
        }
    }

    public TransactionResponse get(@NotNull UUID id) {
        var transaction = store.get(id);
        if (transaction.destinationAccountId() != null)
            throw new TransactionException(TransactionException.Code.TRANSACTION_NOT_FOUND, "Transaction simple introuvable : " + id);
        return transaction;
    }
    public TransactionPageResponse list(@NotNull UUID accountId, @Min(0) int page, @Min(1) @Max(100) int size) {
        return store.list(accountId, page, size);
    }
}
