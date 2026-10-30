package ma.enset.adria.account.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ma.enset.adria.account.domain.Account;
import ma.enset.adria.account.domain.AccountStatus;
import ma.enset.adria.account.repository.AccountRepository;
import ma.enset.adria.account.service.dto.AccountPageResponse;
import ma.enset.adria.account.service.dto.AccountResponse;
import ma.enset.adria.account.service.dto.ChangeAccountStatusRequest;
import ma.enset.adria.account.service.dto.CreateAccountRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@Service
@Validated
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository repository;

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AccountResponse create(@NotNull @Valid CreateAccountRequest request) {
        Account account = new Account(request.customerReference(), AccountStatus.ACTIVE,
                request.currency(), request.balance());
        return toResponse(repository.save(account));
    }

    public AccountResponse get(@NotNull UUID id) {
        return toResponse(findAccount(id));
    }

    public AccountPageResponse list(@Min(0) int page, @Min(1) @Max(100) int size) {
        var accounts = repository.findAll(PageRequest.of(page, size, Sort.by("id")));
        return new AccountPageResponse(accounts.map(this::toResponse).getContent(),
                accounts.getNumber(), accounts.getSize(), accounts.getTotalElements(), accounts.getTotalPages());
    }

    @Transactional
    public AccountResponse changeStatus(@NotNull UUID id, @NotNull @Valid ChangeAccountStatusRequest request) {
        Account account = repository.findLockedById(id).orElseThrow(() -> new AccountNotFoundException(id));
        account.changeStatus(request.status());
        return toResponse(account);
    }

    private Account findAccount(UUID id) {
        return repository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getCustomerReference(), account.getStatus(),
                account.getCurrency(), account.getBalance());
    }
}
