package ma.enset.adria.transaction.service.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 20, fraction = 18) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) { }
