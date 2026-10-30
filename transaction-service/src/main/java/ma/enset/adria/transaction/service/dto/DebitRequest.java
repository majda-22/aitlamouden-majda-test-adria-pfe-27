package ma.enset.adria.transaction.service.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DebitRequest(UUID operationId, UUID accountId, BigDecimal amount, String currency) { }
