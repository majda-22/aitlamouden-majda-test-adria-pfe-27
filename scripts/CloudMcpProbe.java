import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;

/** Client SDK officiel : vérifie le transport MCP au travers de la Gateway. */
class CloudMcpProbe {
    public static void main(String[] args) {
        for (String service : new String[]{"accounts", "transactions"}) {
            try (var client = McpClient.sync(HttpClientStreamableHttpTransport.builder(args[0])
                    .endpoint("/" + service + "/mcp").build()).requestTimeout(Duration.ofSeconds(10)).build()) {
                var init = client.initialize();
                var tools = client.listTools().tools();
                if (tools.size() != (service.equals("accounts") ? 2 : 5)) throw new IllegalStateException("Tools MCP manquants");
                var result = client.callTool(new McpSchema.CallToolRequest(service.equals("accounts") ? "get_account" : "get_transaction",
                        Map.of("id", service.equals("accounts") ? args[1] : args[2])));
                if (Boolean.TRUE.equals(result.isError())) throw new IllegalStateException(result.toString());
                System.out.println("MCP Gateway /" + service + "/mcp : initialized protocol=" + init.protocolVersion()
                        + ", tools=" + tools.size() + ", tools/call isError=" + result.isError());
            }
        }
    }
}
