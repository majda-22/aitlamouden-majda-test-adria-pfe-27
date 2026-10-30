package ma.enset.adria.transaction.web.graphql;

import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.service.TransactionService;
import ma.enset.adria.transaction.service.TransactionException;
import ma.enset.adria.transaction.service.dto.*;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;
import java.util.UUID;

@Controller
public class TransactionGraphQlController {
    private final TransactionService service;
    public TransactionGraphQlController(TransactionService service) { this.service = service; }

    @QueryMapping public TransactionResponse transaction(@Argument UUID id) { return service.get(id); }
    @QueryMapping public TransactionPageResponse transactions(@Argument UUID accountId, @Argument int page, @Argument int size) {
        return service.list(accountId, page, size);
    }
    @QueryMapping public TransferResponse transfer(@Argument UUID id) { return service.getTransfer(id); }

    @MutationMapping public TransactionResponse createTransaction(@Argument CreateTransactionRequest input,
                                                                  @Argument String idempotencyKey) {
        var result = service.create(idempotencyKey, input);
        reject(result.status(), result.rejectionCode(), result.id());
        return result;
    }
    @MutationMapping public TransferResponse createTransfer(@Argument CreateTransferRequest input,
                                                            @Argument String idempotencyKey) {
        var result = service.createTransfer(idempotencyKey, input);
        reject(result.status(), result.rejectionCode(), result.id());
        return result;
    }

    // Traduction de transport identique à REST ; les décisions métier restent dans les services.
    private void reject(TransactionStatus status, String code, UUID id) {
        if (status == TransactionStatus.REJECTED)
            throw new TransactionException(TransactionException.Code.valueOf(code), "Opération " + id + " refusée : " + code);
    }
}
