package ma.enset.adria.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayUnavailableTest {
    @Value("${local.server.port}") int port;
    static final int UNAVAILABLE_PORT = closedPort();
    static int closedPort() {
        try(var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void routes(DynamicPropertyRegistry properties) {
        properties.add("bank.gateway.transactions-uri", () -> "http://127.0.0.1:" + UNAVAILABLE_PORT);
    }
    @Test void failedMutationRoutingReturns503WithoutInventingBankingResult() throws Exception {
        var result = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/transactions"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.body()).contains("SERVICE_UNAVAILABLE").doesNotContain("COMPLETED");
    }
}
