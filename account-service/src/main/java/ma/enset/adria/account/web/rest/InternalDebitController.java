package ma.enset.adria.account.web.rest;

import jakarta.validation.Valid;
import ma.enset.adria.account.service.DebitService;
import ma.enset.adria.account.service.dto.DebitRequest;
import ma.enset.adria.account.service.dto.DebitResponse;
import org.springframework.web.bind.annotation.*;

/** API interne ; authentification interservice à ajouter lors de l'intégration Keycloak. */
@RestController
@RequestMapping("/internal/debits")
public class InternalDebitController {
    private final DebitService service;
    public InternalDebitController(DebitService service) { this.service = service; }

    @PostMapping
    public DebitResponse debit(@Valid @RequestBody DebitRequest request) { return service.debit(request); }
}
