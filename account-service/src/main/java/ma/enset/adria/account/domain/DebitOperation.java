package ma.enset.adria.account.domain;

import jakarta.persistence.*;
import ma.enset.adria.account.service.dto.DebitRequest;
import ma.enset.adria.account.service.dto.DebitResponse;
import ma.enset.adria.account.service.dto.TransferRequest;
import ma.enset.adria.account.service.dto.TransferResponse;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "debit_operations")
public class DebitOperation implements org.springframework.data.domain.Persistable<UUID> {
    @Id
    private UUID operationId;
    @Column(nullable = false)
    private UUID accountId;
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    private String status;
    @Column(precision = 38, scale = 18)
    private BigDecimal balanceAfter;
    private String rejectionCode;
    private UUID destinationAccountId;
    @Column(precision = 38, scale = 18)
    private BigDecimal destinationBalanceAfter;

    protected DebitOperation() { }

    @Override
    public UUID getId() { return operationId; }

    @Override
    @Transient
    public boolean isNew() { return true; }

    public DebitOperation(DebitRequest request) {
        operationId = request.operationId();
        accountId = request.accountId();
        amount = request.amount();
        currency = request.currency();
    }

    public boolean matches(DebitRequest request) {
        return destinationAccountId == null && accountId.equals(request.accountId()) && amount.compareTo(request.amount()) == 0
                && currency.equals(request.currency());
    }

    public void finish(BigDecimal balance, String rejection) {
        balanceAfter = balance;
        rejectionCode = rejection;
        status = rejection == null ? "COMPLETED" : "REJECTED";
    }

    public DebitResponse response() {
        return new DebitResponse(operationId, accountId, amount, currency, status, balanceAfter, rejectionCode);
    }

    public DebitOperation(TransferRequest request) {
        operationId = request.operationId();
        accountId = request.sourceAccountId();
        destinationAccountId = request.destinationAccountId();
        amount = request.amount();
        currency = request.currency();
    }

    public boolean matches(TransferRequest request) {
        return accountId.equals(request.sourceAccountId())
                && java.util.Objects.equals(destinationAccountId, request.destinationAccountId())
                && amount.compareTo(request.amount()) == 0 && currency.equals(request.currency());
    }

    public void finishTransfer(BigDecimal sourceBalance, BigDecimal destinationBalance, String rejection) {
        finish(sourceBalance, rejection);
        destinationBalanceAfter = destinationBalance;
    }

    public TransferResponse transferResponse() {
        return new TransferResponse(operationId, accountId, destinationAccountId, amount, currency,
                status, balanceAfter, destinationBalanceAfter, rejectionCode);
    }
}
