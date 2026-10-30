package ma.enset.adria.transaction.service.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(UUID operationId, UUID sourceAccountId, UUID destinationAccountId,
                              BigDecimal amount, String currency) { }
