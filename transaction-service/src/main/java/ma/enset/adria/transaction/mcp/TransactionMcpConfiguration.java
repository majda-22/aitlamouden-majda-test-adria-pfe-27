package ma.enset.adria.transaction.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import ma.enset.adria.transaction.service.*;
import ma.enset.adria.transaction.service.dto.*;
import ma.enset.adria.transaction.domain.TransactionStatus;
import org.springframework.context.annotation.*;
import java.util.*;
import static ma.enset.adria.transaction.mcp.McpTools.*;

@Configuration(proxyBeanMethods = false)
public class TransactionMcpConfiguration {
    @Bean
    List<McpServerFeatures.SyncToolSpecification> transactionMcpTools(TransactionService service) {
        var key = Map.<String,Object>of("type", "string", "minLength", 1, "maxLength", 128,
                "description", "Clé d'idempotence obligatoire. Réutiliser la même clé avec les mêmes données pour reprendre sans double mouvement.");
        return List.of(
            tool("get_transaction", "LECTURE : consulter un débit simple par UUID, statut et code de rejet. Montant exact en chaîne ; date ISO-8601 UTC.", true,
                schema(Map.of("id", uuid()), "id"), args -> service.get(id(args, "id"))),
            tool("list_transactions", "LECTURE : lister les débits simples d'un compte. page >= 0, size 1 à 100 ; défauts 0 et 10. Aucun mouvement bancaire.", true,
                schema(Map.of("accountId", uuid(), "page", integer(0), "size", integer(10)), "accountId"),
                args -> service.list(id(args, "accountId"), integer(args, "page", 0), integer(args, "size", 10))),
            tool("get_transfer", "LECTURE : consulter un virement par UUID avec source, destination, statut et code de rejet. Aucun mouvement bancaire.", true,
                schema(Map.of("id", uuid()), "id"), args -> service.getTransfer(id(args, "id"))),
            tool("create_transaction", "ÉCRITURE : enregistrer un débit bancaire réel. Montant en chaîne décimale exacte, clé d'idempotence obligatoire. PENDING signifie résultat non confirmé et reprise possible ; aucune réussite fictive. Les indications ne constituent pas une autorisation.", false,
                schema(Map.of("accountId", uuid(), "amount", amount(), "currency", string("Trois lettres majuscules, exemple MAD."),
                    "merchantCategory", string("Catégorie commerçant obligatoire."), "country", string("Pays obligatoire."), "idempotencyKey", key),
                    "accountId", "amount", "currency", "merchantCategory", "country", "idempotencyKey"), args -> {
                    var result = service.create(text(args, "idempotencyKey"), new CreateTransactionRequest(id(args, "accountId"),
                        decimal(args), text(args, "currency"), text(args, "merchantCategory"), text(args, "country")));
                    reject(result.status(), result.rejectionCode(), result.id()); return result;
                }),
            tool("create_transfer", "ÉCRITURE : créer un virement atomique réel entre deux comptes distincts. Montant exact en chaîne, clé d'idempotence obligatoire. PENDING reste récupérable avec la même clé. Indications descriptives, aucune authentification fournie.", false,
                schema(Map.of("sourceAccountId", uuid(), "destinationAccountId", uuid(), "amount", amount(),
                    "currency", string("Trois lettres majuscules, exemple MAD."), "idempotencyKey", key),
                    "sourceAccountId", "destinationAccountId", "amount", "currency", "idempotencyKey"), args -> {
                    var result = service.createTransfer(text(args, "idempotencyKey"), new CreateTransferRequest(id(args, "sourceAccountId"),
                        id(args, "destinationAccountId"), decimal(args), text(args, "currency")));
                    reject(result.status(), result.rejectionCode(), result.id()); return result;
                })
        );
    }

    private void reject(TransactionStatus status, String code, UUID id) {
        if (status == TransactionStatus.REJECTED) throw new TransactionException(TransactionException.Code.valueOf(code),
                "Opération " + id + " refusée : " + code);
    }
}
