package com.smartwallet.common.dto;

import com.smartwallet.common.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable value object representing a transaction request flowing between services
 * via Kafka or REST.
 *
 * ════════════════════════════════════════════════════════════════════
 * TRADITIONAL JAVA (Java 8 / 11) approach
 * ════════════════════════════════════════════════════════════════════
 *
 *   public final class TransactionDto {
 *       private final UUID transactionId;
 *       private final String sourceAccountId;
 *       private final BigDecimal amount;
 *       // ... 8 more fields
 *
 *       // Mandatory: private constructor
 *       private TransactionDto(Builder b) { this.transactionId = b.transactionId; ... }
 *
 *       // Mandatory: getters for every field
 *       public UUID getTransactionId() { return transactionId; }
 *       // ... 8 more getters
 *
 *       // Mandatory: equals(), hashCode(), toString() — all hand-written or Lombok
 *       @Override public boolean equals(Object o) { ... }
 *       @Override public int hashCode() { ... }
 *       @Override public String toString() { ... }
 *
 *       // Builder: boilerplate per field
 *       public static class Builder {
 *           private UUID transactionId;
 *           public Builder transactionId(UUID v) { this.transactionId = v; return this; }
 *           // ... repeated for every field
 *           public TransactionDto build() { return new TransactionDto(this); }
 *       }
 *   }
 *
 *   Problem: ~120 lines of pure boilerplate. Lombok @Builder + @Value reduces it,
 *   but adds a compile-time annotation processor dependency and hides the contract.
 *
 * ════════════════════════════════════════════════════════════════════
 * JAVA 21 approach: Records + compact canonical constructor for validation
 * ════════════════════════════════════════════════════════════════════
 *
 *   A record declaration automatically provides:
 *     • Private final fields
 *     • Public accessor methods (transactionId(), amount(), etc.) — note: no "get" prefix
 *     • equals() and hashCode() based on ALL components
 *     • toString() including all components
 *     • A canonical constructor that can be made compact for validation
 *
 *   What we add manually:
 *     • A compact canonical constructor for guard clauses (no field assignments needed —
 *       the compiler inserts them after the compact constructor body)
 *     • A static nested Builder for ergonomic construction at call sites
 *     • A withXxx() "wither" pattern for non-destructive field updates (since records
 *       are immutable, you can't set fields — you create a modified copy)
 *
 *   Line count: ~80 lines vs ~120. More importantly, the contract is crystal-clear:
 *   this is a pure data carrier. There is no state mutation surface.
 */
public record TransactionDto(
        UUID transactionId,
        String idempotencyKey,
        String sourceAccountId,
        BigDecimal amount,
        Currency currency,
        TransactionType type,       // sealed hierarchy — see TransactionType.java
        TransactionStatus status,
        String initiatedByUserId,
        Instant createdAt,
        Instant processedAt         // null until processing completes
) {

    // ── Compact canonical constructor — validation only, no field assignments ──

    /**
     * The compact canonical constructor runs BEFORE the implicit field assignments
     * the compiler injects. Use it purely for validation. Never assign fields here —
     * the compiler does that for you from the parameter names.
     */
    public TransactionDto {
        Objects.requireNonNull(transactionId,      "transactionId must not be null");
        Objects.requireNonNull(idempotencyKey,     "idempotencyKey must not be null");
        Objects.requireNonNull(sourceAccountId,    "sourceAccountId must not be null");
        Objects.requireNonNull(amount,             "amount must not be null");
        Objects.requireNonNull(currency,           "currency must not be null");
        Objects.requireNonNull(type,               "type must not be null");
        Objects.requireNonNull(status,             "status must not be null");
        Objects.requireNonNull(initiatedByUserId,  "initiatedByUserId must not be null");
        Objects.requireNonNull(createdAt,          "createdAt must not be null");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Transaction amount must be positive, got: " + amount);
        }
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }

    // ── Wither methods — return a modified copy (records are immutable) ────────

    /**
     * TRADITIONAL: you would call a setter (mutable) or re-invoke the full builder.
     * JAVA 21: a "wither" produces a new record instance with one field changed —
     * immutability preserved, intent clear at call site.
     *
     *   dto.withStatus(TransactionStatus.PROCESSED)
     *        .withProcessedAt(Instant.now())
     */
    public TransactionDto withStatus(TransactionStatus newStatus) {
        return new TransactionDto(
                transactionId, idempotencyKey, sourceAccountId,
                amount, currency, type, newStatus,
                initiatedByUserId, createdAt, processedAt);
    }

    public TransactionDto withProcessedAt(Instant timestamp) {
        return new TransactionDto(
                transactionId, idempotencyKey, sourceAccountId,
                amount, currency, type, status,
                initiatedByUserId, createdAt, timestamp);
    }

    // ── Static factory — preferred for simple, fully-specified construction ───

    public static TransactionDto of(
            String idempotencyKey,
            String sourceAccountId,
            BigDecimal amount,
            String currencyCode,
            TransactionType type,
            String initiatedByUserId) {

        return new TransactionDto(
                UUID.randomUUID(),
                idempotencyKey,
                sourceAccountId,
                amount,
                Currency.getInstance(currencyCode),
                type,
                TransactionStatus.INITIATED,
                initiatedByUserId,
                Instant.now(),
                null);
    }

    // ── Builder — for complex construction with optional fields ───────────────

    /**
     * TRADITIONAL: Lombok @Builder or hand-written Builder class.
     * JAVA 21 preference: use the static factory or the canonical constructor directly
     * when all fields are required. Only reach for Builder when you have many optional
     * fields or need staged construction across several call sites.
     *
     * This builder is a NATIVE Java builder — no Lombok processor required.
     * It uses Java 21 text blocks in its toString() and fluent setters.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private UUID transactionId = UUID.randomUUID();
        private String idempotencyKey;
        private String sourceAccountId;
        private BigDecimal amount;
        private Currency currency = Currency.getInstance("INR");
        private TransactionType type;
        private TransactionStatus status = TransactionStatus.INITIATED;
        private String initiatedByUserId;
        private Instant createdAt = Instant.now();
        private Instant processedAt;

        private Builder() {}

        public Builder transactionId(UUID v)           { transactionId = v;      return this; }
        public Builder idempotencyKey(String v)         { idempotencyKey = v;     return this; }
        public Builder sourceAccountId(String v)        { sourceAccountId = v;    return this; }
        public Builder amount(BigDecimal v)              { amount = v;             return this; }
        public Builder currency(String currencyCode)    { currency = Currency.getInstance(currencyCode); return this; }
        public Builder type(TransactionType v)           { type = v;               return this; }
        public Builder status(TransactionStatus v)       { status = v;             return this; }
        public Builder initiatedByUserId(String v)       { initiatedByUserId = v;  return this; }
        public Builder createdAt(Instant v)              { createdAt = v;          return this; }
        public Builder processedAt(Instant v)            { processedAt = v;        return this; }

        public TransactionDto build() {
            // Record's canonical constructor runs all validations automatically
            return new TransactionDto(
                    transactionId, idempotencyKey, sourceAccountId,
                    amount, currency, type, status,
                    initiatedByUserId, createdAt, processedAt);
        }
    }

    // ── Convenience query methods ─────────────────────────────────────────────

    /**
     * JAVA 21 — pattern matching in an instance method.
     * TRADITIONAL: instanceof chain with explicit casting and separate null-checks.
     */
    public boolean isPeerToPeer() {
        return type instanceof TransactionType.PeerToPeer;
    }

    public boolean isHighValue(BigDecimal threshold) {
        return amount.compareTo(threshold) > 0;
    }

    /**
     * Enum for transaction lifecycle states.
     * Kept as a nested type since it belongs to the DTO's contract.
     */
    public enum TransactionStatus {
        INITIATED,
        PENDING_FRAUD_CHECK,
        FRAUD_FLAGGED,
        FRAUD_BLOCKED,
        PROCESSING,
        PROCESSED,
        FAILED,
        REVERSED
    }
}
