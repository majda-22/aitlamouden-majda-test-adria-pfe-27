package ma.enset.adria.transaction.service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(UUID id, UUID accountId, BigDecimal amount, String currency,
                                  String merchantCategory, String country, Instant createdAt,
                                  ma.enset.adria.transaction.domain.TransactionStatus status, String rejectionCode) {
}
