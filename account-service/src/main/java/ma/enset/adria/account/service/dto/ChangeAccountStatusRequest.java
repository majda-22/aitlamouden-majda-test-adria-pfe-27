package ma.enset.adria.account.service.dto;

import jakarta.validation.constraints.NotNull;
import ma.enset.adria.account.domain.AccountStatus;

public record ChangeAccountStatusRequest(@NotNull AccountStatus status) {
}
