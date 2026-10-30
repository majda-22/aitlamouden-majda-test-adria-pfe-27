package ma.enset.adria.account.web.rest;

import jakarta.validation.Valid;
import ma.enset.adria.account.service.TransferService;
import ma.enset.adria.account.service.dto.*;
import org.springframework.web.bind.annotation.*;

/** API interne non authentifiée ; sécurisation interservice à ajouter avec Keycloak. */
@RestController
@RequestMapping("/internal/transfers")
public class InternalTransferController {
    private final TransferService service;
    public InternalTransferController(TransferService service) { this.service = service; }
    @PostMapping public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return service.transfer(request);
    }
}
