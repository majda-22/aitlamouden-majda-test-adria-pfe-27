package ma.enset.adria.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServiceTest {
    @Value("${local.server.port}") int port;
    HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void startsWithNativeConfigurationAndHealth() throws Exception {
        var health=get("/actuator/health"); assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }
    @Test void sharesConfiguredCeilingWithTransactionClient() throws Exception {
        var result=get("/transaction-service/cloud"); assertThat(result.statusCode()).isEqualTo(200);
        var body=JsonMapper.builder().build().readTree(result.body());
        assertThat(body.path("name").asText()).isEqualTo("transaction-service");
        assertThat(body.path("propertySources").toString()).contains("bank.transaction.max-amount","1000");
    }
}
