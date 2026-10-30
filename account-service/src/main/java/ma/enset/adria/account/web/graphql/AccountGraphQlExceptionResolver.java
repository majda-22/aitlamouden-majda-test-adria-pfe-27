package ma.enset.adria.account.web.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import ma.enset.adria.account.service.AccountNotFoundException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;
import java.util.Map;

@Component
public class AccountGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        String code;
        String message;
        ErrorType type;
        if (exception instanceof AccountNotFoundException) {
            code = "ACCOUNT_NOT_FOUND"; message = exception.getMessage(); type = ErrorType.NOT_FOUND;
        } else if (exception instanceof ConstraintViolationException || exception instanceof BindException) {
            code = "INVALID_INPUT"; message = "Entrée ou paramètres invalides."; type = ErrorType.BAD_REQUEST;
        } else return null;
        return GraphqlErrorBuilder.newError(environment).errorType(type).message(message)
                .extensions(Map.of("code", code)).build();
    }
}
