package ma.enset.adria.account.service.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferResponse(UUID operationId, UUID sourceAccountId, UUID destinationAccountId,
        BigDecimal amount, String currency, String status, BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter, String rejectionCode) { }
