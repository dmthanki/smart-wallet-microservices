package com.smartwallet.transaction.kafka;

import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.transaction.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class VerdictConsumer {

    private static final Logger log = LoggerFactory.getLogger(VerdictConsumer.class);
    private static final String TOPIC = "fraud.verdict";

    private final TransactionService transactionService;

    public VerdictConsumer(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Consumes FraudVerdictEvents from the fraud.verdict topic.
     * With spring.threads.virtual.enabled: true, this consumer runs on Project Loom virtual threads,
     * freeing up platorm threads while blocking on downstream database locks during settlement.
     */
    @KafkaListener(topics = TOPIC, groupId = "transaction-service-group")
    public void onVerdict(FraudVerdictEvent event) {
        log.info("Received fraud verdict for transaction [{}]: verdict={}, score={}",
                event.transactionId(), event.verdict(), event.riskScore());
        try {
            transactionService.applyVerdict(event);
            log.info("Successfully applied fraud verdict for transaction [{}]", event.transactionId());
        } catch (Exception e) {
            log.error("Failed to apply fraud verdict for transaction [{}]: {}", 
                    event.transactionId(), e.getMessage(), e);
        }
    }
}
