package com.smartwallet.notification.outbox;

import com.smartwallet.notification.domain.NotificationPayload;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the notification_outbox table.
 *
 * Lifecycle:
 *   PENDING  → written when a Kafka event is consumed, before the webhook fires
 *   SENT     → updated after a successful 2xx response from n8n
 *   FAILED   → updated after a non-retryable error or exhausted retries
 *   RETRYING → scheduler has picked it up for another attempt
 *   DEAD     → max retries exceeded; needs manual investigation
 *
 * Design notes:
 *   • The entity is intentionally a traditional mutable JPA class (not a record)
 *     because Hibernate requires a no-arg constructor, mutable state, and
 *     proxy sub-classing — none of which records support.
 *   • The payload is stored as JSONB. The scheduler re-serialises it when
 *     retrying — no need to re-consume Kafka.
 *   • nextRetryAt enables exponential back-off without a cron expression per row.
 */
@Entity
@Table(
    name = "notification_outbox",
    indexes = {
        @Index(name = "idx_outbox_status_next_retry",
               columnList = "status, next_retry_at ASC"),
        @Index(name = "idx_outbox_transaction_id",
               columnList = "transaction_id"),
        @Index(name = "idx_outbox_pending",
               columnList = "created_at ASC")   // filtered by status = 'PENDING' in SQL
    }
)
public class NotificationOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "account_id", nullable = false, length = 100)
    private String accountId;

    @Column(name = "recipient_user_id", nullable = false, length = 100)
    private String recipientUserId;

    @Column(name = "alert_severity", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private NotificationPayload.Severity alertSeverity;

    /**
     * Full serialised NotificationPayload as JSONB.
     * Stored so the scheduler can retry without re-consuming from Kafka.
     */
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "status", nullable = false, length = 15)
    @Enumerated(EnumType.STRING)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 5;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    // ── Lifecycle hooks ───────────────────────────────────────────────────────

    @PrePersist
    void onCreate() {
        createdAt    = Instant.now();
        nextRetryAt  = createdAt;           // eligible for dispatch immediately
        if (status == null) status = OutboxStatus.PENDING;
    }

    // ── State machine methods ─────────────────────────────────────────────────

    /** Called by the scheduler/client on a successful 2xx webhook response. */
    public void markSent() {
        this.status          = OutboxStatus.SENT;
        this.sentAt          = Instant.now();
        this.lastAttemptedAt = sentAt;
        this.lastError       = null;
    }

    /**
     * Called on a failed dispatch attempt.
     * Applies exponential back-off: nextRetryAt = now + (retryCount² × backoffBase).
     *
     * @param error         the exception message or HTTP status reason
     * @param backoffBase   base seconds for back-off calculation
     */
    public void markFailed(String error, long backoffBase) {
        this.retryCount++;
        this.lastError       = truncate(error, 1000);
        this.lastAttemptedAt = Instant.now();

        if (this.retryCount >= this.maxRetries) {
            this.status = OutboxStatus.DEAD;
        } else {
            this.status     = OutboxStatus.FAILED;
            long delaySeconds = (long) Math.pow(retryCount, 2) * backoffBase;
            this.nextRetryAt = Instant.now().plusSeconds(delaySeconds);
        }
    }

    /** Called by scheduler when picking up the record for a retry attempt. */
    public void markRetrying() {
        this.status = OutboxStatus.RETRYING;
    }

    public boolean isRetryable() {
        return (status == OutboxStatus.PENDING
                || status == OutboxStatus.FAILED
                || status == OutboxStatus.RETRYING)
               && retryCount < maxRetries
               && (nextRetryAt == null || !Instant.now().isBefore(nextRetryAt));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public UUID getId()                               { return id; }
    public UUID getTransactionId()                    { return transactionId; }
    public String getEventType()                      { return eventType; }
    public String getAccountId()                      { return accountId; }
    public String getRecipientUserId()                { return recipientUserId; }
    public NotificationPayload.Severity getAlertSeverity() { return alertSeverity; }
    public String getPayload()                        { return payload; }
    public OutboxStatus getStatus()                   { return status; }
    public int getRetryCount()                        { return retryCount; }
    public int getMaxRetries()                        { return maxRetries; }
    public String getLastError()                      { return lastError; }
    public Instant getNextRetryAt()                   { return nextRetryAt; }
    public Instant getCreatedAt()                     { return createdAt; }
    public Instant getLastAttemptedAt()               { return lastAttemptedAt; }
    public Instant getSentAt()                        { return sentAt; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final NotificationOutboxEntity e = new NotificationOutboxEntity();

        public Builder transactionId(UUID v)                          { e.transactionId = v;    return this; }
        public Builder eventType(String v)                            { e.eventType = v;         return this; }
        public Builder accountId(String v)                            { e.accountId = v;         return this; }
        public Builder recipientUserId(String v)                      { e.recipientUserId = v;   return this; }
        public Builder alertSeverity(NotificationPayload.Severity v)  { e.alertSeverity = v;     return this; }
        public Builder payload(String v)                              { e.payload = v;           return this; }
        public Builder status(OutboxStatus v)                         { e.status = v;            return this; }
        public Builder maxRetries(int v)                              { e.maxRetries = v;        return this; }
        public NotificationOutboxEntity build()                       { return e; }
    }

    protected NotificationOutboxEntity() {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    // ── Status enum ───────────────────────────────────────────────────────────

    public enum OutboxStatus {
        PENDING,    // newly written, not yet attempted
        RETRYING,   // currently being attempted by scheduler
        SENT,       // successfully dispatched
        FAILED,     // last attempt failed, will retry
        DEAD        // max retries exceeded
    }
}
