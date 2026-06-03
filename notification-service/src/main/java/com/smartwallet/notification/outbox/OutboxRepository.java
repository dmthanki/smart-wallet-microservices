package com.smartwallet.notification.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<NotificationOutboxEntity, UUID> {

    /**
     * Fetches a batch of retryable records in a single query for the scheduler.
     * Ordered by nextRetryAt ASC so oldest-due records are processed first.
     *
     * The SKIP LOCKED hint prevents two concurrent scheduler instances from
     * picking up the same rows — critical for horizontal scaling.
     */
    @Query(value = """
            SELECT *
            FROM   notification_outbox
            WHERE  status IN ('PENDING', 'FAILED', 'RETRYING')
            AND    next_retry_at <= :now
            ORDER  BY next_retry_at ASC
            LIMIT  :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationOutboxEntity> findRetryableBatch(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    /**
     * Count of records in a given status — used by the actuator health check
     * and Prometheus metrics gauge.
     */
    long countByStatus(NotificationOutboxEntity.OutboxStatus status);

    /**
     * Bulk-mark stale RETRYING records back to FAILED so they re-enter the
     * retry queue. Guards against records stuck in RETRYING if the scheduler
     * crashes mid-batch.
     */
    @Modifying
    @Query("""
            UPDATE NotificationOutboxEntity o
            SET    o.status = 'FAILED'
            WHERE  o.status = com.smartwallet.notification.outbox.NotificationOutboxEntity.OutboxStatus.RETRYING
            AND    o.lastAttemptedAt < :staleThreshold
            """)
    int resetStaleRetrying(@Param("staleThreshold") Instant staleThreshold);

    List<NotificationOutboxEntity> findByTransactionId(UUID transactionId);
}
