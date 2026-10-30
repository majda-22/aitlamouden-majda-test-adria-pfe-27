package ma.enset.adria.transaction.service;

import feign.FeignException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ma.enset.adria.transaction.domain.Transaction;
import ma.enset.adria.transaction.repository.TransactionRepository;
import ma.enset.adria.transaction.service.client.AccountClient;
import ma.enset.adria.transaction.service.dto.AccountSnapshot;
import ma.enset.adria.transaction.service.dto.CreateTransactionRequest;
import ma.enset.adria.transaction.service.dto.TransactionPageResponse;
import ma.enset.adria.transaction.service.dto.TransactionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static ma.enset.adria.transaction.service.TransactionException.Code.*;

@Service
@Validated
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository repository;
    private final AccountClient accountClient;
    private final BigDecimal maxAmount;

    public TransactionService(TransactionRepository repository, AccountClient accountClient,
                              @Value("${bank.transaction.max-amount}") BigDecimal maxAmount) {
        this.repository = repository;
        this.accountClient = accountClient;
        if (maxAmount.signum() <= 0) {
            throw new IllegalArgumentException("bank.transaction.max-amount doit être strictement positif.");
        }
        this.maxAmount = maxAmount;
    }

    @Transactional
    public TransactionResponse create(@NotNull @Valid CreateTransactionRequest request) {
        if (request.amount().compareTo(maxAmount) > 0) {
            throw new TransactionException(MAX_AMOUNT_EXCEEDED, "Le montant dépasse le plafond de " + maxAmount + ".");
        }
        AccountSnapshot account = consultAccount(request.accountId());
        if ("BLOCKED".equals(account.status())) {
            throw new TransactionException(ACCOUNT_BLOCKED, "Le compte est bloqué.");
        }
        if (!request.currency().equals(account.currency())) {
            throw new TransactionException(CURRENCY_MISMATCH, "La devise doit correspondre à celle du compte.");
        }
        if (request.amount().compareTo(account.balance()) > 0) {
            throw new TransactionException(INSUFFICIENT_BALANCE, "Le solde du compte est insuffisant.");
        }

        // Étape intermédiaire : consultation seule, aucun débit du compte n'est effectué.
        Transaction transaction = new Transaction(request.accountId(), request.amount(), request.currency(),
                request.merchantCategory(), request.country(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        return toResponse(repository.save(transaction));
    }

    public TransactionResponse get(@NotNull UUID id) {
        return toResponse(repository.findById(id).orElseThrow(() ->
                new TransactionException(TRANSACTION_NOT_FOUND, "Transaction introuvable : " + id)));
    }

    public TransactionPageResponse list(@NotNull UUID accountId, @Min(0) int page, @Min(1) @Max(100) int size) {
        var transactions = repository.findByAccountId(accountId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new TransactionPageResponse(transactions.map(this::toResponse).getContent(),
                transactions.getNumber(), transactions.getSize(), transactions.getTotalElements(), transactions.getTotalPages());
    }

    private AccountSnapshot consultAccount(UUID id) {
        AccountSnapshot account;
        try {
            account = accountClient.getAccount(id);
        } catch (FeignException.NotFound exception) {
            throw new TransactionException(ACCOUNT_NOT_FOUND, "Compte introuvable : " + id, exception);
        } catch (FeignException exception) {
            throw new TransactionException(ACCOUNT_SERVICE_UNAVAILABLE,
                    "Impossible de consulter account-service.", exception);
        }
        if (account == null || !id.equals(account.id())
                || !("ACTIVE".equals(account.status()) || "BLOCKED".equals(account.status()))
                || account.currency() == null || !account.currency().matches("[A-Z]{3}")
                || account.balance() == null || account.balance().signum() < 0) {
            throw new TransactionException(ACCOUNT_SERVICE_UNAVAILABLE, "Réponse de account-service invalide.");
        }
        return account;
    }

    private TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getAccountId(), transaction.getAmount(),
                transaction.getCurrency(), transaction.getMerchantCategory(), transaction.getCountry(), transaction.getCreatedAt());
    }
}
