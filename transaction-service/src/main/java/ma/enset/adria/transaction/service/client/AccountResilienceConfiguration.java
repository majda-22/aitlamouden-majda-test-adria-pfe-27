package ma.enset.adria.transaction.service.client;

import feign.Retryer;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Configuration(proxyBeanMethods = false)
public class AccountResilienceConfiguration {
    public static final String CIRCUIT_NAME = "accountService";

    @Bean
    CircuitBreakerNameResolver accountCircuitName() {
        return (clientName, target, method) -> CIRCUIT_NAME;
    }

    @Bean
    Retryer noFeignRetries() { return Retryer.NEVER_RETRY; }

    @Bean
    HttpClient5FeignConfiguration.HttpClientBuilderCustomizer noTransportRetries() {
        return builder -> builder.disableAutomaticRetries();
    }

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> accountCircuitLogs() {
        Set<String> observed = ConcurrentHashMap.newKeySet();
        return factory -> factory.addCircuitBreakerCustomizer(circuit -> {
            if (observed.add(circuit.getName())) circuit.getEventPublisher().onStateTransition(event ->
                    LoggerFactory.getLogger(AccountResilienceConfiguration.class)
                            .info("Circuit {} : {}", event.getCircuitBreakerName(), event.getStateTransition()));
        }, CIRCUIT_NAME);
    }
}
