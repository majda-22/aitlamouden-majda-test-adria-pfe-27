package ma.enset.adria.transaction.web.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.StringValue;
import graphql.schema.*;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import org.springframework.context.annotation.*;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.ResponseError;
import java.math.BigDecimal;
import java.util.*;

@Configuration(proxyBeanMethods = false)
public class GraphQlConfiguration {
    @Bean
    RuntimeWiringConfigurer decimalScalar() {
        var decimal = GraphQLScalarType.newScalar().name("Decimal")
                .description("Chaîne décimale exacte sans exposant, exemple : \"250.00\".")
                .coercing(new Coercing<BigDecimal, String>() {
                    @Override public String serialize(Object value, GraphQLContext context, Locale locale) {
                        if (value instanceof BigDecimal amount) return amount.toPlainString();
                        throw new CoercingSerializeException("Decimal attend un BigDecimal.");
                    }
                    @Override public BigDecimal parseValue(Object value, GraphQLContext context, Locale locale) {
                        return parse(value);
                    }
                    @Override public BigDecimal parseLiteral(Value<?> value, CoercedVariables variables,
                                                             GraphQLContext context, Locale locale) {
                        if (value instanceof StringValue text) {
                            try { return parse(text.getValue()); }
                            catch (CoercingParseValueException error) { throw new CoercingParseLiteralException(error.getMessage()); }
                        }
                        throw new CoercingParseLiteralException("Decimal attend une chaîne décimale exacte.");
                    }
                    private BigDecimal parse(Object value) {
                        if (!(value instanceof String text) || !text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
                            throw new CoercingParseValueException("Decimal attend une chaîne décimale sans exposant, exemple : 250.00.");
                        return new BigDecimal(text);
                    }
                }).build();
        return wiring -> wiring.scalar(decimal);
    }

    @Bean
    WebGraphQlInterceptor structuredRequestErrors() {
        return (request, chain) -> chain.next(request).map(response -> response.transform(builder ->
                builder.errors(response.getErrors().stream().map(this::withCode).toList())));
    }

    private GraphQLError withCode(ResponseError error) {
        var extensions = new LinkedHashMap<String, Object>();
        if (error.getExtensions() != null) extensions.putAll(error.getExtensions());
        String classification = error.getErrorType().toString();
        extensions.putIfAbsent("code", classification.equals("ValidationError") || classification.equals("InvalidSyntax")
                ? "INVALID_INPUT" : "INTERNAL_ERROR");
        var builder = GraphqlErrorBuilder.newError().message(error.getMessage())
                .errorType(error.getErrorType()).extensions(extensions);
        if (error.getPath() != null) builder.path(error.getParsedPath());
        if (error.getLocations() != null) builder.locations(error.getLocations());
        return builder.build();
    }
}
