package ma.enset.adria.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;

/** Liste fermée de routes publiques ; aucun routage automatique des services Eureka. */
@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {
    @Bean
    RouteLocator bankRoutes(RouteLocatorBuilder routes,
            @Value("${bank.gateway.accounts-uri}") String accounts,
            @Value("${bank.gateway.transactions-uri}") String transactions) {
        return routes.routes()
            .route("account-rest", r -> r.path("/api/accounts", "/api/accounts/**").uri(accounts))
            .route("transaction-rest", r -> r.path("/api/transactions", "/api/transactions/**", "/api/transfers", "/api/transfers/**").uri(transactions))
            .route("account-graphql", r -> r.path("/accounts/graphql")
                .filters(f -> f.stripPrefix(1)).uri(accounts))
            .route("transaction-graphql", r -> r.path("/transactions/graphql")
                .filters(f -> f.stripPrefix(1)).uri(transactions))
            // Les connexions MCP peuvent rester ouvertes en SSE ; ne pas leur appliquer le timeout de réponse REST.
            .route("account-mcp", r -> r.path("/accounts/mcp").filters(f -> f.stripPrefix(1))
                .metadata("response-timeout", -1).uri(accounts))
            .route("transaction-mcp", r -> r.path("/transactions/mcp").filters(f -> f.stripPrefix(1))
                .metadata("response-timeout", -1).uri(transactions))
            .build();
    }
}
