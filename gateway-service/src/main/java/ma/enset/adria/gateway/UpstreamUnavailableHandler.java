package ma.enset.adria.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Une panne de connexion reste une erreur HTTP, jamais une réponse métier de substitution. */
@Component
public class UpstreamUnavailableHandler implements WebExceptionHandler, Ordered {
    @Override public int getOrder() { return -2; }
    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        boolean unavailable = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof io.netty.handler.timeout.ReadTimeoutException) unavailable = true;
        }
        if (!unavailable || exchange.getResponse().isCommitted()) return Mono.error(error);
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Service bancaire indisponible.\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
