package com.smartwallet.transaction.repository;

import com.smartwallet.transaction.domain.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Polls a batch of unprocessed outbox events.
     * SKIP LOCKED prevents concurrent pollers from picking up the same rows.
     */
    @Query(value = """
            SELECT *
            FROM   outbox_events
            WHERE  processed = false
            ORDER  BY created_at ASC
            LIMIT  :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> findUnprocessedBatch(@Param("batchSize") int batchSize);
}
