package com.smartwallet.fraud.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.fraud.domain.FraudLogEntity;
import com.smartwallet.fraud.engine.FraudRuleEngine;
import com.smartwallet.fraud.repository.FraudLogRepository;
import com.smartwallet.fraud.repository.TransactionLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Kafka consumer for the fraud-detection-service.
 *
 * Listens on {@code txn.created} topic, evaluates the transaction,
 * persists the results in Postgres, and publishes a FraudVerdictEvent back onto {@code fraud.verdict}.
 */
@Component
public class FraudAnalysisConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudAnalysisConsumer.class);
    private static final String INPUT_TOPIC   = "txn.created";
    private static final String OUTPUT_TOPIC  = "fraud.verdict";

    private final FraudRuleEngine engine;
    private final TransactionLogStore logStore;
    private final FraudLogRepository fraudLogRepo;
    private final KafkaTemplate<String, FraudVerdictEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FraudAnalysisConsumer(
            FraudRuleEngine engine,
            TransactionLogStore logStore,
            FraudLogRepository fraudLogRepo,
            KafkaTemplate<String, FraudVerdictEvent> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.engine       = engine;
        this.logStore     = logStore;
        this.fraudLogRepo = fraudLogRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes a single TransactionDto from the txn.created topic.
     */
    @KafkaListener(topics = INPUT_TOPIC, groupId = "fraud-detection-group")
    public void onTransaction(TransactionDto tx) {
        log.info("Received txn [{}] for fraud evaluation", tx.transactionId());

        try {
            // Step 1: Record in velocity log BEFORE evaluating
            logStore.record(tx);

            // Step 2: Evaluate
            FraudVerdictEvent verdict = engine.evaluate(tx);

            // Step 3: Persist evaluation log in fraud_logs table
            persistFraudLog(tx, verdict);

            // Step 4: Publish verdict — keyed by sourceAccountId for partition locality
            kafkaTemplate.send(OUTPUT_TOPIC, tx.sourceAccountId(), verdict);

            log.info("Verdict [{}] published for txn [{}], score={}",
                    verdict.verdict(), tx.transactionId(), verdict.riskScore());

        } catch (Exception e) {
            log.error("Evaluation failed for txn [{}]: {}", tx.transactionId(), e.getMessage(), e);
            // Fail-open policy: publish a PASS verdict so transactions do not hang in PENDING_FRAUD_CHECK
            kafkaTemplate.send(OUTPUT_TOPIC, tx.sourceAccountId(),
                    FraudVerdictEvent.pass(tx.transactionId()));
        }
    }

    private void persistFraudLog(TransactionDto tx, FraudVerdictEvent verdict) {
        try {
            FraudLogEntity entity = new FraudLogEntity();
            entity.setTransactionId(tx.transactionId());
            entity.setSourceAccountId(tx.sourceAccountId());
            entity.setRiskScore(BigDecimal.valueOf(verdict.riskScore()));
            entity.setVerdict(verdict.verdict().name());
            entity.setTriggeredRules(objectMapper.writeValueAsString(verdict.triggeredRules()));
            entity.setAnalysisSummary(verdict.analysisSummary());
            
            fraudLogRepo.save(entity);
            log.debug("Fraud evaluation log persisted for transaction [{}]", tx.transactionId());
        } catch (Exception e) {
            log.error("Failed to persist fraud log for transaction [{}]: {}", tx.transactionId(), e.getMessage());
        }
    }
}
