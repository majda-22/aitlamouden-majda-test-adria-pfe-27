package ma.enset.adria.account.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Adaptation de transport uniquement ; les règles bancaires restent dans les services. */
final class McpTools {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static Map<String,Object> string(String description) { return Map.of("type", "string", "description", description); }
    static Map<String,Object> uuid() { return Map.of("type", "string", "format", "uuid", "description", "UUID du compte ou de l'opération."); }
    static Map<String,Object> amount() { return Map.of("type", "string", "pattern", "-?(0|[1-9][0-9]*)(\\.[0-9]+)?", "description", "Montant décimal exact en chaîne, sans exposant, exemple : 250.00."); }
    static Map<String,Object> integer(int value) { return Map.of("type", "integer", "default", value); }
    static Map<String,Object> schema(Map<String,Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false);
    }
    static McpServerFeatures.SyncToolSpecification tool(String name, String description, boolean readOnly,
            Map<String,Object> schema, Function<Map<String,Object>,Object> call) {
        var tool = McpSchema.Tool.builder(name).description(description)
                .inputSchema(schema).annotations(new McpSchema.ToolAnnotations(name, readOnly, !readOnly, true, false, false)).build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String,Object> args = request.arguments() == null ? Map.of() : request.arguments();
                @SuppressWarnings("unchecked") var properties = (Map<String,Object>) schema.get("properties");
                if (!properties.keySet().containsAll(args.keySet())) throw new IllegalArgumentException("Champ inconnu.");
                for (Object key : (List<?>) schema.get("required")) if (args.get(key) == null) throw new IllegalArgumentException("Champ obligatoire : " + key);
                Object result = call.apply(args);
                return result(false, normalize(result));
            } catch (RuntimeException exception) {
                return error(exception);
            }
        });
    }
    static McpSchema.CallToolResult result(boolean error, Object value) {
        return McpSchema.CallToolResult.builder().isError(error).structuredContent(value)
                .addTextContent(JSON.writeValueAsString(value)).build();
    }
    static UUID id(Map<String,Object> args, String name) { return UUID.fromString(text(args, name)); }
    static String text(Map<String,Object> args, String name) {
        if (!(args.get(name) instanceof String text)) throw new IllegalArgumentException("Chaîne obligatoire : " + name);
        return text;
    }
    static BigDecimal decimal(Map<String,Object> args) {
        String amount = text(args, "amount");
        if (!amount.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new IllegalArgumentException("Format décimal invalide.");
        return new BigDecimal(amount);
    }
    static int integer(Map<String,Object> args, String name, int fallback) {
        if (!args.containsKey(name)) return fallback;
        if (!(args.get(name) instanceof Number number)) throw new IllegalArgumentException("Entier attendu : " + name);
        return new BigDecimal(number.toString()).intValueExact();
    }
    static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d.toPlainString();
        if (value instanceof UUID || value instanceof Instant || value instanceof Enum<?>) return value.toString();
        if (value instanceof List<?> values) return values.stream().map(McpTools::normalize).toList();
        if (value.getClass().isRecord()) {
            Map<String,Object> result = new LinkedHashMap<>();
            try {
                for (var field : value.getClass().getRecordComponents()) result.put(field.getName(), normalize(field.getAccessor().invoke(value)));
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
            return result;
        }
        return value;
    }
    static McpSchema.CallToolResult error(RuntimeException exception) {
        String code = "INTERNAL_ERROR";
        String message = "Erreur interne lors de l'exécution du tool.";
        if (exception instanceof ma.enset.adria.account.service.AccountNotFoundException) {
            code = "ACCOUNT_NOT_FOUND"; message = exception.getMessage();
        }
        if (exception instanceof IllegalArgumentException || exception instanceof ArithmeticException
                || exception instanceof jakarta.validation.ConstraintViolationException) {
            code = "INVALID_INPUT"; message = "Entrée ou paramètres invalides.";
        }
        return result(true, Map.of("code", code, "message", message));
    }
}
