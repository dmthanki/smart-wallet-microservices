package com.smartwallet.transaction.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the accounts table.
 *
 * Aggregate root for the account balance lifecycle:
 *   availableBalance  — can be spent, not reserved
 *   reservedBalance   — escrowed for in-flight transactions
 *   totalBalance      = available + reserved
 *
 * JAVA 21 NOTE on Records vs JPA:
 * ─────────────────────────────────
 * JPA entities CANNOT be records. JPA requires:
 *   1. A no-arg constructor (records don't have one)
 *   2. Mutable fields (records are immutable)
 *   3. Proxy-ability (Hibernate creates subclass proxies; records are final)
 *
 * The correct approach: keep JPA entities as traditional mutable classes,
 * and map them to RECORDS (TransactionDto) at the service boundary.
 * Records live in the service/API layer; entities live in the persistence layer.
 * This is the "Anti-Corruption Layer" pattern — the persistence model and the
 * domain model are deliberately separate.
 */
@Entity
@Table(name = "accounts",
        indexes = {
            @Index(name = "idx_accounts_owner",   columnList = "owner_id"),
            @Index(name = "idx_accounts_number",  columnList = "account_number", unique = true)
        })
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedBalance = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "merchant_id")
    private String merchantId; // only set for merchant accounts

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    // ── Balance operations ────────────────────────────────────────────────────

    public void reserveBalance(BigDecimal amount) {
        if (availableBalance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "Cannot reserve %s: only %s available".formatted(amount, availableBalance));
        }
        availableBalance = availableBalance.subtract(amount);
        reservedBalance  = reservedBalance.add(amount);
    }

    public void commitReservation(BigDecimal amount) {
        if (reservedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot commit reservation: insufficient reserved funds");
        }
        reservedBalance = reservedBalance.subtract(amount);
        // availableBalance is already reduced — nothing to do
    }

    public void releaseReservation(BigDecimal amount) {
        reservedBalance  = reservedBalance.subtract(amount);
        availableBalance = availableBalance.add(amount);
    }

    public void credit(BigDecimal amount) {
        availableBalance = availableBalance.add(amount);
    }

    // ── Standard accessors ────────────────────────────────────────────────────

    public UUID getId()                  { return id; }
    public String getOwnerId()           { return ownerId; }
    public String getAccountNumber()     { return accountNumber; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public BigDecimal getReservedBalance()  { return reservedBalance; }
    public String getCurrency()          { return currency; }
    public String getStatus()            { return status; }
    public String getMerchantId()        { return merchantId; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }

    // Required by JPA
    protected AccountEntity() {}

    public AccountEntity(UUID id, String ownerId, String accountNumber, String accountType, BigDecimal availableBalance, BigDecimal reservedBalance, String currency, String status, String merchantId) {
        this.id = id;
        this.ownerId = ownerId;
        this.accountNumber = accountNumber;
        this.accountType = accountType;
        this.availableBalance = availableBalance;
        this.reservedBalance = reservedBalance;
        this.currency = currency;
        this.status = status;
        this.merchantId = merchantId;
    }
}
