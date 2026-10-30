package ma.enset.adria.account.service.dto;

import ma.enset.adria.account.domain.AccountStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String customerReference,
        AccountStatus status,
        String currency,
        BigDecimal balance
) {
}
