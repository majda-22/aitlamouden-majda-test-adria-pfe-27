package ma.enset.adria.transaction.web.rest;

import jakarta.validation.Valid;
import ma.enset.adria.transaction.service.TransactionService;
import ma.enset.adria.transaction.service.dto.CreateTransactionRequest;
import ma.enset.adria.transaction.service.dto.TransactionPageResponse;
import ma.enset.adria.transaction.service.dto.TransactionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import ma.enset.adria.transaction.domain.TransactionStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody CreateTransactionRequest request) {
        var transaction = service.create(key, request);
        if (transaction.status() == TransactionStatus.REJECTED) {
            throw new ma.enset.adria.transaction.service.TransactionException(
                    ma.enset.adria.transaction.service.TransactionException.Code.valueOf(transaction.rejectionCode()),
                    "Transaction " + transaction.id() + " refusée : " + transaction.rejectionCode());
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(transaction.id()).toUri();
        return ResponseEntity.status(transaction.status() == TransactionStatus.PENDING ? 202 : 201)
                .location(location).body(transaction);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public TransactionPageResponse list(@RequestParam UUID accountId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return service.list(accountId, page, size);
    }
}
