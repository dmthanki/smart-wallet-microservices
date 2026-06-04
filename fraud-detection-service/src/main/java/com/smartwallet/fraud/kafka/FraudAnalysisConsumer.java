package com.smartwallet.fraud.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.fraud.domain.FraudLogEntity;
import com.smartwallet.fraud.engine.FraudRuleEngine;
import com.smartwallet.fraud.repository.FraudLogRepository;
import com.smartwallet.fraud.repository.TransactionLogStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
     * Processes a single transaction from the txn.created topic.
     * Handles both TransactionDto instances and fallback JSON String payloads.
     */
    @KafkaListener(topics = INPUT_TOPIC, groupId = "fraud-detection-group")
    public void onTransaction(ConsumerRecord<String, Object> record) {
        Object value = record.value();
        TransactionDto tx;
        if (value instanceof TransactionDto) {
            tx = (TransactionDto) value;
        } else if (value instanceof String json) {
            try {
                tx = objectMapper.readValue(json, TransactionDto.class);
            } catch (Exception e) {
                try {
                    tx = deserializeTransactionFallback(json);
                } catch (Exception ex) {
                    log.error("Failed to deserialize JSON string to TransactionDto: {}", json, ex);
                    return;
                }
            }
        } else {
            log.error("Unknown payload type received on topic txn.created: {}", 
                    value != null ? value.getClass().getName() : "null");
            return;
        }

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

    @SuppressWarnings("unchecked")
    private TransactionDto deserializeTransactionFallback(String json) throws Exception {
        java.util.Map<String, Object> map = objectMapper.readValue(json, java.util.Map.class);
        
        java.util.UUID transactionId = java.util.UUID.fromString((String) map.get("transactionId"));
        String idempotencyKey = (String) map.get("idempotencyKey");
        String sourceAccountId = (String) map.get("sourceAccountId");
        
        java.math.BigDecimal amount;
        Object amtObj = map.get("amount");
        if (amtObj instanceof Number) {
            amount = java.math.BigDecimal.valueOf(((Number) amtObj).doubleValue());
        } else {
            amount = new java.math.BigDecimal((String) amtObj);
        }
        
        java.util.Currency currency = java.util.Currency.getInstance((String) map.get("currency"));
        
        java.util.Map<String, Object> typeMap = (java.util.Map<String, Object>) map.get("type");
        com.smartwallet.common.enums.TransactionType type;
        if (typeMap.containsKey("recipientAccountId")) {
            type = new com.smartwallet.common.enums.TransactionType.PeerToPeer(
                    (String) typeMap.get("recipientAccountId"),
                    (String) typeMap.get("note")
            );
        } else if (typeMap.containsKey("merchantId")) {
            type = new com.smartwallet.common.enums.TransactionType.MerchantPayment(
                    (String) typeMap.get("merchantId"),
                    (String) typeMap.get("merchantName"),
                    typeMap.get("mcc") != null ? ((Number) typeMap.get("mcc")).intValue() : 0
            );
        } else if (typeMap.containsKey("destinationBankCode")) {
            type = new com.smartwallet.common.enums.TransactionType.Withdrawal(
                    (String) typeMap.get("destinationBankCode"),
                    typeMap.get("instantTransfer") != null && (boolean) typeMap.get("instantTransfer")
            );
        } else if (typeMap.containsKey("sourceReference")) {
            type = new com.smartwallet.common.enums.TransactionType.Deposit(
                    (String) typeMap.get("sourceReference")
            );
        } else {
            throw new IllegalArgumentException("Cannot determine transaction type from payload: " + json);
        }
        
        TransactionDto.TransactionStatus status = TransactionDto.TransactionStatus.valueOf((String) map.get("status"));
        String initiatedByUserId = (String) map.get("initiatedByUserId");
        java.time.Instant createdAt = java.time.Instant.parse((String) map.get("createdAt"));
        
        java.time.Instant processedAt = null;
        if (map.get("processedAt") != null) {
            processedAt = java.time.Instant.parse((String) map.get("processedAt"));
        }
        
        return new TransactionDto(
                transactionId, idempotencyKey, sourceAccountId,
                amount, currency, type, status,
                initiatedByUserId, createdAt, processedAt
        );
    }
}
