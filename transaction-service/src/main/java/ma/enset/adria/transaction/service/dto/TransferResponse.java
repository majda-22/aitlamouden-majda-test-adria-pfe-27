package ma.enset.adria.transaction.service.dto;

import ma.enset.adria.transaction.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(UUID id, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
        String currency, Instant createdAt, TransactionStatus status, String rejectionCode) { }
