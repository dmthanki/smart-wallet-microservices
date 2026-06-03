package com.smartwallet.transaction.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events",
        indexes = {
            @Index(name = "idx_outbox_unprocessed", columnList = "created_at ASC")
        })
public class OutboxEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "processed", nullable = false)
    private boolean processed = false;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public boolean isProcessed() { return processed; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public void setTopic(String topic) { this.topic = topic; }
    public void setPayload(String payload) { this.payload = payload; }
    
    public void markProcessed() {
        this.processed = true;
        this.processedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final OutboxEventEntity e = new OutboxEventEntity();

        public Builder aggregateType(String v) { e.aggregateType = v; return this; }
        public Builder aggregateId(UUID v)     { e.aggregateId = v;   return this; }
        public Builder eventType(String v)     { e.eventType = v;     return this; }
        public Builder topic(String v)         { e.topic = v;         return this; }
        public Builder payload(String v)       { e.payload = v;       return this; }
        public OutboxEventEntity build()       { return e; }
    }
}
