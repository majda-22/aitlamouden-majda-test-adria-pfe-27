package ma.enset.adria.transaction.web.rest;

import jakarta.validation.Valid;
import ma.enset.adria.transaction.domain.TransactionStatus;
import ma.enset.adria.transaction.service.*;
import ma.enset.adria.transaction.service.dto.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
    private final TransactionService service;
    public TransferController(TransactionService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody CreateTransferRequest request) {
        var transfer = service.createTransfer(key, request);
        if (transfer.status() == TransactionStatus.REJECTED)
            throw new TransactionException(TransactionException.Code.valueOf(transfer.rejectionCode()),
                    "Virement " + transfer.id() + " refusé : " + transfer.rejectionCode());
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(transfer.id()).toUri();
        return ResponseEntity.status(transfer.status() == TransactionStatus.PENDING ? 202 : 201).location(location).body(transfer);
    }

    @GetMapping("/{id}") public TransferResponse get(@PathVariable UUID id) { return service.getTransfer(id); }
}
