package ma.enset.adria.transaction.service;

import ma.enset.adria.transaction.domain.*;
import ma.enset.adria.transaction.repository.TransactionRepository;
import ma.enset.adria.transaction.service.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static ma.enset.adria.transaction.service.TransactionException.Code.*;

/** Transactions SQL courtes ; aucun appel HTTP dans ce composant. */
@Service
@Transactional(readOnly = true)
public class TransactionStore {
    private final TransactionRepository repository;
    public TransactionStore(TransactionRepository repository) { this.repository = repository; }

    @Transactional
    public TransactionResponse reserve(String key, CreateTransactionRequest request, BigDecimal maxAmount) {
        var existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) return matching(existing.get(), request);
        if (request.amount().compareTo(maxAmount) > 0)
            throw new TransactionException(MAX_AMOUNT_EXCEEDED, "Le montant dépasse le plafond de " + maxAmount);
        var transaction = new Transaction(request.accountId(), request.amount(), request.currency(),
                request.merchantCategory(), request.country(), Instant.now().truncatedTo(ChronoUnit.MICROS), key);
        return response(repository.saveAndFlush(transaction));
    }

    public TransactionResponse replay(String key, CreateTransactionRequest request) {
        return matching(repository.findByIdempotencyKey(key).orElseThrow(), request);
    }

    private TransactionResponse matching(Transaction t, CreateTransactionRequest r) {
        if (!t.getAccountId().equals(r.accountId()) || t.getAmount().compareTo(r.amount()) != 0
                || !t.getCurrency().equals(r.currency()) || !t.getMerchantCategory().equals(r.merchantCategory())
                || !t.getCountry().equals(r.country()))
            throw new TransactionException(IDEMPOTENCY_CONFLICT, "Cette clé d'idempotence est associée à d'autres données.");
        return response(t);
    }

    public TransactionResponse get(UUID id) { return response(find(id)); }

    @Transactional
    public TransactionResponse finish(UUID id, TransactionStatus status, String rejection) {
        var transaction = repository.findLockedById(id).orElseThrow();
        transaction.finish(status, rejection);
        return response(transaction);
    }

    public List<UUID> pending() {
        return repository.findByStatus(TransactionStatus.PENDING, PageRequest.of(0, 20, Sort.by("createdAt")))
                .map(Transaction::getId).getContent();
    }

    public TransactionPageResponse list(UUID accountId, int page, int size) {
        var transactions = repository.findByAccountId(accountId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new TransactionPageResponse(transactions.map(this::response).getContent(), page, size,
                transactions.getTotalElements(), transactions.getTotalPages());
    }

    private Transaction find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TransactionException(TRANSACTION_NOT_FOUND, "Transaction introuvable : " + id));
    }

    private TransactionResponse response(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccountId(), t.getAmount(), t.getCurrency(),
                t.getMerchantCategory(), t.getCountry(), t.getCreatedAt(), t.getStatus(), t.getRejectionCode());
    }
}
