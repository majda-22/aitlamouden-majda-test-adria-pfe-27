package ma.enset.adria.transaction.web.rest;

import jakarta.validation.ConstraintViolationException;
import ma.enset.adria.transaction.service.TransactionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException.class)
    public ProblemDetail handleRemoteUnavailability(RuntimeException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "account-service indisponible.");
        problem.setProperty("code", "ACCOUNT_SERVICE_UNAVAILABLE");
        return problem;
    }

    @ExceptionHandler(TransactionException.class)
    public ProblemDetail handleTransactionException(TransactionException exception) {
        HttpStatus status = switch (exception.getCode()) {
            case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND, TRANSFER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACCOUNT_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case IDEMPOTENCY_CONFLICT, OPERATION_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setProperty("code", exception.getCode().name());
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Paramètres invalides.");
        problem.setProperty("errors", exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " : " + violation.getMessage()).sorted().toList());
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Données invalides.");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " : " + error.getDefaultMessage()).sorted().toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
