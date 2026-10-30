package ma.enset.adria.account.web.graphql;

import ma.enset.adria.account.domain.AccountStatus;
import ma.enset.adria.account.service.AccountService;
import ma.enset.adria.account.service.dto.*;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;
import java.util.UUID;

@Controller
public class AccountGraphQlController {
    private final AccountService service;
    public AccountGraphQlController(AccountService service) { this.service = service; }

    @QueryMapping public AccountResponse account(@Argument UUID id) { return service.get(id); }
    @QueryMapping public AccountPageResponse accounts(@Argument int page, @Argument int size) {
        return service.list(page, size);
    }
    @MutationMapping public AccountResponse createAccount(@Argument CreateAccountRequest input) {
        return service.create(input);
    }
    @MutationMapping public AccountResponse updateAccountStatus(@Argument UUID id, @Argument AccountStatus status) {
        return service.changeStatus(id, new ChangeAccountStatusRequest(status));
    }
}
