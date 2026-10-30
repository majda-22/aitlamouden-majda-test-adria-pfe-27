package ma.enset.adria.account;

import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import ma.enset.adria.account.service.AccountService;
import ma.enset.adria.account.service.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=jdbc:h2:mem:account-mcp-test;DB_CLOSE_DELAY=-1")
class AccountMcpTest {
    @Value("${local.server.port}") int port;
    @Autowired AccountService service;

    @Test void officialClientInitializesDiscoversAndCallsReadTools() {
        var account = service.create(new CreateAccountRequest("MCP", "MAD", new BigDecimal("1500.000000000000000001")));
        try (var client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp").build()).requestTimeout(Duration.ofSeconds(10)).build()) {
            var initialized = client.initialize();
            assertThat(initialized.serverInfo().name()).isEqualTo("bank-accounts");
            var tools = client.listTools().tools();
            assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("get_account", "list_accounts");
            assertThat(tools).allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());
            var result = client.callTool(new McpSchema.CallToolRequest("get_account", Map.of("id", account.id().toString())));
            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContent().toString()).contains("1500.000000000000000001", account.id().toString());
            assertThat(client.callTool(new McpSchema.CallToolRequest("list_accounts", Map.of("page", 0,"size", 1))).isError()).isFalse();
            var missing = client.callTool(new McpSchema.CallToolRequest("get_account", Map.of("id", UUID.randomUUID().toString())));
            assertThat(missing.isError()).isTrue(); assertThat(missing.structuredContent().toString()).contains("ACCOUNT_NOT_FOUND");
            var invalid = client.callTool(new McpSchema.CallToolRequest("list_accounts", Map.of("page", -1)));
            assertThat(invalid.isError()).isTrue(); assertThat(invalid.structuredContent().toString()).contains("INVALID_INPUT");
        }
    }
}
