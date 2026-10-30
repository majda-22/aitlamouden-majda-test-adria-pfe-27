package ma.enset.adria.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServiceTest {
    @Value("${local.server.port}") int port;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    HttpResponse<String> request(String method,String path,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10))
            .header("Accept","application/json").header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void startsHealthyAndServesEurekaRegistry() throws Exception {
        assertThat(request("GET","/actuator/health",null).body()).contains("\"status\":\"UP\"");
        assertThat(request("GET","/eureka/apps",null).statusCode()).isEqualTo(200);
    }
    @Test void acceptsRegistrationHeartbeatAndCancellation() throws Exception {
        String instance="{\"instance\":{\"instanceId\":\"test-instance\",\"app\":\"TEST-SERVICE\",\"hostName\":\"localhost\","
            +"\"ipAddr\":\"127.0.0.1\",\"status\":\"UP\",\"port\":{\"$\":12345,\"@enabled\":\"true\"},"
            +"\"dataCenterInfo\":{\"@class\":\"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo\",\"name\":\"MyOwn\"}}}";
        assertThat(request("POST","/eureka/apps/TEST-SERVICE",instance).statusCode()).isEqualTo(204);
        try {
            assertThat(request("GET","/eureka/apps/TEST-SERVICE/test-instance",null).body()).contains("test-instance","UP");
            assertThat(request("PUT","/eureka/apps/TEST-SERVICE/test-instance",null).statusCode()).isEqualTo(200);
        } finally { assertThat(request("DELETE","/eureka/apps/TEST-SERVICE/test-instance",null).statusCode()).isEqualTo(200); }
    }
}
