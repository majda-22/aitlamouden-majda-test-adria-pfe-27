package ma.enset.adria.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bank_transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String merchantCategory;

    @Column(nullable = false, updatable = false)
    private String country;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Le défaut SQL s'applique aux lignes existantes lors de l'ajout de la colonne.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(32) default 'LEGACY_UNDEBITED'")
    private TransactionStatus status = TransactionStatus.LEGACY_UNDEBITED;

    @Column(unique = true, length = 128, updatable = false)
    private String idempotencyKey;

    private String rejectionCode;

    protected Transaction() {
    }

    public Transaction(UUID accountId, BigDecimal amount, String currency,
                       String merchantCategory, String country, Instant createdAt, String idempotencyKey) {
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.merchantCategory = merchantCategory;
        this.country = country;
        this.createdAt = createdAt;
        this.idempotencyKey = idempotencyKey;
        this.status = TransactionStatus.PENDING;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getMerchantCategory() { return merchantCategory; }
    public String getCountry() { return country; }
    public Instant getCreatedAt() { return createdAt; }
    public TransactionStatus getStatus() { return status; }
    public String getRejectionCode() { return rejectionCode; }

    public void finish(TransactionStatus status, String rejectionCode) {
        if (this.status == TransactionStatus.PENDING) {
            this.status = status;
            this.rejectionCode = rejectionCode;
        }
    }
}
