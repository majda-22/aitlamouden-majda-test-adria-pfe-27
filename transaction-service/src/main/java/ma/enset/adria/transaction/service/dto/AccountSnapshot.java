package ma.enset.adria.transaction.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountSnapshot(UUID id, String status, String currency, BigDecimal balance) {
}
