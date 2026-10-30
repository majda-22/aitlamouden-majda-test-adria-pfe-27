package ma.enset.adria.account.web.rest;

import jakarta.validation.Valid;
import ma.enset.adria.account.service.AccountService;
import ma.enset.adria.account.service.dto.AccountPageResponse;
import ma.enset.adria.account.service.dto.AccountResponse;
import ma.enset.adria.account.service.dto.ChangeAccountStatusRequest;
import ma.enset.adria.account.service.dto.CreateAccountRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse account = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(account.id()).toUri();
        return ResponseEntity.created(location).body(account);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public AccountPageResponse list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @PatchMapping("/{id}/status")
    public AccountResponse changeStatus(@PathVariable UUID id,
                                       @Valid @RequestBody ChangeAccountStatusRequest request) {
        return service.changeStatus(id, request);
    }
}
