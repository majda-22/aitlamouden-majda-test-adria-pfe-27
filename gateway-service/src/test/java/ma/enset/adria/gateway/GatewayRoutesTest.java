package ma.enset.adria.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutesTest {
    static final ConcurrentLinkedQueue<String> REQUESTS=new ConcurrentLinkedQueue<>();
    static final HttpServer ACCOUNT=server("account");
    static final HttpServer TRANSACTION=server("transaction");
    @Value("${local.server.port}") int port;
    static HttpServer server(String name) {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange -> {
                REQUESTS.add(name+":"+exchange.getRequestMethod()+":"+exchange.getRequestURI().getPath());
                String status=exchange.getRequestURI().getPath().equals("/api/transactions/pending")?"PENDING":"UP";
                byte[] bytes=("{\"service\":\""+name+"\",\"path\":\""+exchange.getRequestURI().getPath()+"\",\"status\":\""+status+"\"}").getBytes();
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/api/accounts/unavailable") ? 503 : 200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });server.start();return server;
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void routes(DynamicPropertyRegistry properties) {
        properties.add("bank.gateway.accounts-uri",()->"http://127.0.0.1:"+ACCOUNT.getAddress().getPort());
        properties.add("bank.gateway.transactions-uri",()->"http://127.0.0.1:"+TRANSACTION.getAddress().getPort());
    }
    @AfterAll static void stop(){ACCOUNT.stop(0);TRANSACTION.stop(0);}
    @BeforeEach void reset(){REQUESTS.clear();}
    HttpResponse<String> request(String method,String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void startsHealthy() throws Exception {
        var result=request("GET","/actuator/health");assertThat(result.statusCode()).isEqualTo(200);assertThat(result.body()).contains("UP");
    }
    @Test void routesRestToCorrectServiceWithoutChangingPaths() throws Exception {
        assertThat(request("GET","/api/accounts?page=0&size=10").body()).contains("account");
        assertThat(request("POST","/api/transactions").body()).contains("transaction");
        assertThat(request("POST","/api/transfers").body()).contains("transaction");
        assertThat(REQUESTS).containsExactly("account:GET:/api/accounts","transaction:POST:/api/transactions","transaction:POST:/api/transfers");
    }
    @Test void routesGraphQlAndMcpWithPrefixRemovedAndAllMcpMethods() throws Exception {
        for(String prefix:List.of("accounts","transactions")) {
            assertThat(request("POST","/"+prefix+"/graphql").statusCode()).isEqualTo(200);
            for(String method:List.of("POST","GET","DELETE")) assertThat(request(method,"/"+prefix+"/mcp").statusCode()).isEqualTo(200);
        }
        assertThat(REQUESTS).hasSize(8).allSatisfy(s->assertThat(s).doesNotContain("/accounts/","/transactions/"));
    }
    @Test void refusesInternalAndDiscoveryGeneratedRoutesWithoutCallingUpstream() throws Exception {
        for(String path:List.of("/internal/debits","/internal/transfers","/accounts/internal/debits","/transactions/internal/transfers",
                "/account-service/internal/debits","/accounts/mcp/../internal/debits","/accounts/%69nternal/debits")) {
            assertThat(request("POST",path).statusCode()).isIn(400,404);
        }
        assertThat(REQUESTS).isEmpty();
    }
    @Test void preservesPendingBodyAndDoesNotAnnounceSuccess() throws Exception {
        var result=request("GET","/api/transactions/pending");
        assertThat(result.body()).contains("PENDING").doesNotContain("COMPLETED");
        assertThat(REQUESTS).hasSize(1);
    }
    @Test void upstreamServerFailureReturnsErrorWithoutRetryOrSuccessBody() throws Exception {
        var result=request("GET","/api/accounts/unavailable");
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(REQUESTS).hasSize(1);
        assertThat(result.body()).doesNotContain("COMPLETED");
    }
}
