package ma.enset.adria.transaction;

import com.sun.net.httpserver.HttpServer;
import ma.enset.adria.transaction.service.*;
import ma.enset.adria.transaction.service.dto.CreateTransactionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.net.*;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CloudConfigurationTest {
    @Test void cloudProfileImportsRemoteConfigurationIntoExistingBankingService() throws Exception {
        var config = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        config.createContext("/",exchange -> {
            byte[] bytes=("{\"name\":\"transaction-service\",\"profiles\":[\"cloud\"],\"propertySources\":["
                +"{\"name\":\"isolated-config-server\",\"source\":{\"bank.transaction.max-amount\":\"125\"}}]}").getBytes();
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });config.start();
        try(var context = new SpringApplicationBuilder(TransactionServiceApplication.class).web(WebApplicationType.NONE)
            .run("--spring.profiles.active=cloud","--CONFIG_SERVER_URL=http://127.0.0.1:"+config.getAddress().getPort(),
                "--eureka.client.enabled=false","--bank.transaction.recovery.enabled=false",
                "--spring.datasource.url=jdbc:h2:mem:cloud-config-test;DB_CLOSE_DELAY=-1")) {
            assertThat(context.getEnvironment().getProperty("bank.transaction.max-amount")).isEqualTo("125");
            assertThatThrownBy(() -> context.getBean(TransactionService.class).create("remote-ceiling-test",
                new CreateTransactionRequest(UUID.randomUUID(),new BigDecimal("126"),"MAD","SHOP","MA")))
                .isInstanceOfSatisfying(TransactionException.class,error ->
                    assertThat(error.getCode()).isEqualTo(TransactionException.Code.MAX_AMOUNT_EXCEEDED));
        } finally { config.stop(0); }
    }
    @Test void cloudProfileFailsStartupWhenRequiredConfigServerIsUnavailable() throws Exception {
        int port;
        try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        final int closed=port;
        assertThatThrownBy(() -> new SpringApplicationBuilder(TransactionServiceApplication.class).web(WebApplicationType.NONE)
            .run("--spring.profiles.active=cloud","--CONFIG_SERVER_URL=http://127.0.0.1:"+closed,
                "--eureka.client.enabled=false","--spring.cloud.config.request-connect-timeout=500",
                "--spring.cloud.config.request-read-timeout=500"))
            .hasStackTraceContaining("Could not locate PropertySource");
    }
}
