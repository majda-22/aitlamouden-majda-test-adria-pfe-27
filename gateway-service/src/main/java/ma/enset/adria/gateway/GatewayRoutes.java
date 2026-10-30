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
            .route("account-protocols", r -> r.path("/accounts/graphql", "/accounts/mcp")
                .filters(f -> f.stripPrefix(1)).uri(accounts))
            .route("transaction-protocols", r -> r.path("/transactions/graphql", "/transactions/mcp")
                .filters(f -> f.stripPrefix(1)).uri(transactions))
            .build();
    }
}
