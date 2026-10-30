package ma.enset.adria.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Défense complémentaire : aucune variante préfixée/encodée d'un chemin interne n'est publiée. */
@Component
public class InternalPathFilter implements WebFilter, Ordered {
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.contains("%") || path.contains(";") || path.contains("\\")
                || java.util.Arrays.asList(path.split("/")).contains("..")
                || java.util.Arrays.asList(path.split("/")).contains("internal")) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
