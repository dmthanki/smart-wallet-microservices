package com.smartwallet.transaction.outbox;

import com.smartwallet.transaction.domain.OutboxEventEntity;
import com.smartwallet.transaction.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${transaction.outbox.batch-size:20}")
    private int batchSize;

    public OutboxPoller(OutboxEventRepository outboxRepo, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Periodically polls the outbox_events table for unprocessed rows,
     * publishes them to Kafka, and marks them as processed.
     *
     * FOR UPDATE SKIP LOCKED provides concurrent safety across multiple replicas.
     */
    @Scheduled(fixedDelayString = "${transaction.outbox.poll-interval-ms:5000}")
    @Transactional
    public void pollAndPublish() {
        log.debug("Polling unprocessed outbox events");

        List<OutboxEventEntity> batch = outboxRepo.findUnprocessedBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        log.info("Found {} unprocessed outbox events to publish", batch.size());

        for (OutboxEventEntity event : batch) {
            try {
                // Key the Kafka partition by aggregate ID (e.g., transaction ID) for ordering guarantees
                String partitionKey = event.getAggregateId().toString();

                // Send to Kafka
                kafkaTemplate.send(event.getTopic(), partitionKey, event.getPayload());

                // Mark as processed in local DB
                event.markProcessed();
                outboxRepo.save(event);

                log.info("Successfully published outbox event [id={}, type={}] to topic [{}]",
                        event.getId(), event.getEventType(), event.getTopic());

            } catch (Exception e) {
                log.error("Failed to publish outbox event [id={}]: {}", event.getId(), e.getMessage(), e);
                // We do NOT abort the transaction for other events in the batch;
                // this failing row will simply be retried on the next scheduler tick.
            }
        }
    }
}
