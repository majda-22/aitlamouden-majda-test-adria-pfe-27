package ma.enset.adria.account.service;

import java.util.UUID;

public class DebitException extends RuntimeException {
    private final UUID operationId;
    private final String code;

    public DebitException(UUID operationId, String code) {
        super("Débit refusé : " + code);
        this.operationId = operationId;
        this.code = code;
    }

    public UUID getOperationId() { return operationId; }
    public String getCode() { return code; }
}
