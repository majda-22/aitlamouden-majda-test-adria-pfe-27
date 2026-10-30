package ma.enset.adria.account.service.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DebitResponse(UUID operationId, UUID accountId, BigDecimal amount, String currency,
                            String status, BigDecimal balanceAfter, String rejectionCode) {
}
