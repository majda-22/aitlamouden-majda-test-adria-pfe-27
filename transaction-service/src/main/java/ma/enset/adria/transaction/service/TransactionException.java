package ma.enset.adria.transaction.service;

public class TransactionException extends RuntimeException {

    public enum Code {
        MAX_AMOUNT_EXCEEDED,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_BLOCKED,
        CURRENCY_MISMATCH,
        INSUFFICIENT_BALANCE,
        ACCOUNT_SERVICE_UNAVAILABLE,
        TRANSACTION_NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        OPERATION_CONFLICT
    }

    private final Code code;

    public TransactionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public TransactionException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
