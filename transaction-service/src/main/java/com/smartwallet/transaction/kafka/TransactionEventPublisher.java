package com.smartwallet.transaction.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.transaction.domain.OutboxEventEntity;
import com.smartwallet.transaction.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);

    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public TransactionEventPublisher(OutboxEventRepository outboxRepo, ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Serialises the transaction and persists it in the local outbox_events table.
     * Guaranteed to execute inside the same active DB transaction as the balance mutate.
     */
    public void publish(TransactionDto transaction) {
        log.info("Persisting outbox event for transaction [{}]", transaction.transactionId());
        try {
            String payloadJson = objectMapper.writeValueAsString(transaction);

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .aggregateType("Transaction")
                    .aggregateId(transaction.transactionId())
                    .eventType("TransactionCreated")
                    .topic("txn.created")
                    .payload(payloadJson)
                    .build();

            outboxRepo.save(event);
            log.debug("Outbox event saved successfully for transaction [{}]", transaction.transactionId());
        } catch (Exception e) {
            log.error("Failed to persist outbox event for transaction [{}]: {}", 
                    transaction.transactionId(), e.getMessage(), e);
            throw new RuntimeException("Outbox persistence failed", e);
        }
    }
}
