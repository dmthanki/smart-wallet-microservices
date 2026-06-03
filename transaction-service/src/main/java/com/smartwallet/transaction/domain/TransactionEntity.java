package com.smartwallet.transaction.domain;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.dto.TransactionDto.TransactionStatus;
import com.smartwallet.common.enums.TransactionType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * JPA entity for the transactions table.
 *
 * Uses Hibernate's JSONB support for the transaction type payload —
 * each TransactionType subtype serialises to a JSONB column that includes
 * a "kind" discriminator field, enabling efficient GIN indexing in PostgreSQL.
 *
 * JAVA 21 mapping note:
 * ─────────────────────
 * TransactionType is a sealed interface hierarchy. We cannot store it directly
 * as a JPA @Embedded (no single flat-column mapping). Instead we:
 *   1. Store the type discriminator in a VARCHAR column ("PEER_TO_PEER", etc.)
 *   2. Store the type-specific payload in a JSONB column
 *   3. Re-hydrate the sealed type in toDto() using a pattern match switch
 *
 * This demonstrates that Java 21's sealed types and records fit the service
 * and API layers best — the persistence layer still needs traditional mappings.
 */
@Entity
@Table(name = "transactions",
        indexes = {
            @Index(name = "idx_txn_source_created", columnList = "source_account_id, created_at DESC"),
            @Index(name = "idx_txn_idempotency",    columnList = "idempotency_key",   unique = true),
            @Index(name = "idx_txn_status",         columnList = "status")
        })
public class TransactionEntity {

    @Id
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "source_account_id", nullable = false)
    private String sourceAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "type_kind", nullable = false, length = 30)
    private String typeKind;

    @Column(name = "type_payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String typePayload; // serialised JSON of the specific subtype

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    @Column(name = "initiated_by_user_id", nullable = false)
    private String initiatedByUserId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    // ── Mapping: DTO → Entity ─────────────────────────────────────────────────

    /**
     * JAVA 21: switch expression with record patterns destructures the sealed type
     * to extract the correct discriminator string.
     */
    public static TransactionEntity from(TransactionDto dto) {
        TransactionEntity e = new TransactionEntity();
        e.id                = dto.transactionId();
        e.idempotencyKey    = dto.idempotencyKey();
        e.sourceAccountId   = dto.sourceAccountId();
        e.amount            = dto.amount();
        e.currency          = dto.currency().getCurrencyCode();
        e.status            = dto.status();
        e.initiatedByUserId = dto.initiatedByUserId();
        e.createdAt         = dto.createdAt();
        e.processedAt       = dto.processedAt();

        // JAVA 21: switch expression — maps sealed subtype to string discriminator
        e.typeKind = switch (dto.type()) {
            case TransactionType.PeerToPeer p      -> "PEER_TO_PEER";
            case TransactionType.Withdrawal w      -> "WITHDRAWAL";
            case TransactionType.Deposit d         -> "DEPOSIT";
            case TransactionType.MerchantPayment m -> "MERCHANT_PAYMENT";
        };

        // In production: use Jackson ObjectMapper to serialise the subtype to JSON
        e.typePayload = serializeType(dto.type());
        return e;
    }

    // ── Mapping: Entity → DTO ─────────────────────────────────────────────────

    /**
     * Re-hydrates the sealed TransactionType from the stored discriminator + payload.
     * JAVA 21: switch expression returns the correct sealed subtype.
     */
    public TransactionDto toDto() {
        TransactionType type = deserializeType(typeKind, typePayload);

        return new TransactionDto(
                id, idempotencyKey, sourceAccountId,
                amount, Currency.getInstance(currency),
                type, status, initiatedByUserId,
                createdAt, processedAt);
    }

    // ── Serialisation helpers (simplified — use Jackson in production) ────────

    private static String serializeType(TransactionType type) {
        // JAVA 21: switch expression for type serialisation
        return switch (type) {
            case TransactionType.PeerToPeer(var r, var n) ->
                    """
                    {"recipientAccountId":"%s","note":"%s"}
                    """.formatted(r, n == null ? "" : n).strip();
            case TransactionType.Withdrawal(var b, var i) ->
                    """
                    {"destinationBankCode":"%s","instantTransfer":%b}
                    """.formatted(b, i).strip();
            case TransactionType.Deposit(var s) ->
                    """
                    {"sourceReference":"%s"}
                    """.formatted(s).strip();
            case TransactionType.MerchantPayment(var id, var name, var mcc) ->
                    """
                    {"merchantId":"%s","merchantName":"%s","mcc":%d}
                    """.formatted(id, name, mcc).strip();
        };
    }

    private static TransactionType deserializeType(String kind, String payload) {
        // Simplified: in production use Jackson to deserialise the payload JSON.
        // The switch expression ensures exhaustive handling of all known discriminators.
        return switch (kind) {
            case "PEER_TO_PEER"    ->
                    new TransactionType.PeerToPeer("parsed-from-json", null);
            case "WITHDRAWAL"      ->
                    new TransactionType.Withdrawal("parsed-from-json", false);
            case "DEPOSIT"         ->
                    new TransactionType.Deposit("parsed-from-json");
            case "MERCHANT_PAYMENT" ->
                    new TransactionType.MerchantPayment("id", "name", 0);
            default -> throw new IllegalStateException("Unknown transaction kind: " + kind);
        };
    }

    protected TransactionEntity() {}
}
