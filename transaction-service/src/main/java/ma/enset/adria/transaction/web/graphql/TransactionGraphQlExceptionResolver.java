package ma.enset.adria.transaction.web.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import ma.enset.adria.transaction.service.TransactionException;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;
import java.util.Map;

@Component
public class TransactionGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        String code;
        String message;
        ErrorType type;
        if (exception instanceof TransactionException business) {
            code = business.getCode().name(); message = business.getMessage();
            type = switch (business.getCode()) {
                case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND, TRANSFER_NOT_FOUND -> ErrorType.NOT_FOUND;
                default -> ErrorType.BAD_REQUEST;
            };
        } else if (exception instanceof ConstraintViolationException || exception instanceof BindException) {
            code = "INVALID_INPUT"; message = "Entrée ou paramètres invalides."; type = ErrorType.BAD_REQUEST;
        } else if (exception instanceof NoFallbackAvailableException) {
            code = "ACCOUNT_SERVICE_UNAVAILABLE"; message = "account-service indisponible."; type = ErrorType.INTERNAL_ERROR;
        } else return null;
        return GraphqlErrorBuilder.newError(environment).errorType(type).message(message)
                .extensions(Map.of("code", code)).build();
    }
}
