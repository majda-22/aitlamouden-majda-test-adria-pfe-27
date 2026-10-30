package ma.enset.adria.account.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import ma.enset.adria.account.service.AccountService;
import org.springframework.context.annotation.*;
import java.util.*;
import static ma.enset.adria.account.mcp.McpTools.*;

@Configuration(proxyBeanMethods = false)
public class AccountMcpConfiguration {
    @Bean
    List<McpServerFeatures.SyncToolSpecification> accountMcpTools(AccountService service) {
        return List.of(
            tool("get_account", "LECTURE : consulter un compte par UUID, statut, devise et solde décimal exact. Ne modifie aucune donnée.", true,
                schema(Map.of("id", uuid()), "id"), args -> service.get(id(args, "id"))),
            tool("list_accounts", "LECTURE : lister les comptes paginés. page >= 0, size de 1 à 100, défauts 0 et 20. Soldes en chaînes décimales exactes.", true,
                schema(Map.of("page", integer(0), "size", integer(20))),
                args -> service.list(integer(args, "page", 0), integer(args, "size", 20)))
        );
    }
}
