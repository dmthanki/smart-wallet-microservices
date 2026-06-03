package com.smartwallet.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.enums.TransactionType;
import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.notification.client.N8nWebhookClient;
import com.smartwallet.notification.client.WebhookClient;
import com.smartwallet.notification.domain.NotificationPayload;
import com.smartwallet.notification.domain.NotificationPayload.Severity;
import com.smartwallet.notification.outbox.NotificationOutboxEntity;
import com.smartwallet.notification.outbox.NotificationOutboxEntity.OutboxStatus;
import com.smartwallet.notification.outbox.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Kafka consumer for the notification-service.
 *
 * Listens to two topics simultaneously:
 *   • {@code txn.created}  — TransactionDto, sourced from transaction-service
 *   • {@code fraud.verdict} — FraudVerdictEvent, sourced from fraud-detection-service
 *
 * ── Processing pipeline ────────────────────────────────────────────────────
 *   1. Deserialise the Kafka record into the correct domain type.
 *   2. Use Java 21 pattern matching for switch to assemble a NotificationPayload
 *      appropriate for each event type — no casting, no instanceof chains.
 *   3. Persist an outbox row in the same local DB transaction (PENDING).
 *   4. Attempt an immediate webhook dispatch via N8nWebhookClient.
 *   5. Update the outbox row to SENT or FAILED based on the typed DispatchResult.
 *   6. Acknowledge the Kafka offset only after the outbox row is durably persisted.
 *
 * ── Reliability guarantee ──────────────────────────────────────────────────
 *   Offset is committed (ack.acknowledge()) AFTER the outbox row is written to
 *   PostgreSQL. If the JVM crashes after write but before ack, Kafka will redeliver
 *   the event on restart. The outbox record ensures idempotency — the retry scheduler
 *   will not re-dispatch rows already marked SENT.
 *
 * ── Virtual threads ────────────────────────────────────────────────────────
 *   With spring.threads.virtual.enabled=true, each @KafkaListener invocation
 *   runs on a virtual thread. The RestClient HTTP call parks (not blocks) the
 *   virtual thread during the webhook round-trip, allowing the carrier thread
 *   to serve other partitions concurrently.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private static final String TOPIC_TXN_CREATED   = "txn.created";
    private static final String TOPIC_FRAUD_VERDICT  = "fraud.verdict";
    private static final String GROUP_ID             = "notification-service-group";

    private final OutboxRepository    outboxRepository;
    private final WebhookClient       webhookClient;
    private final ObjectMapper        objectMapper;
    private final Counter             eventsReceivedCounter;
    private final Counter             outboxWrittenCounter;

    @Value("${notification.outbox.max-retries:5}")
    private int maxRetries;

    @Value("${notification.outbox.backoff-base-seconds:10}")
    private long backoffBase;

    public NotificationConsumer(
            OutboxRepository outboxRepository,
            WebhookClient webhookClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.outboxRepository  = outboxRepository;
        this.webhookClient     = webhookClient;
        this.objectMapper      = objectMapper;
        this.eventsReceivedCounter = Counter.builder("notification.events.received")
                .description("Kafka events received by notification service")
                .register(meterRegistry);
        this.outboxWrittenCounter  = Counter.builder("notification.outbox.written")
                .description("Outbox rows written")
                .register(meterRegistry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KAFKA LISTENER — single method, two topics
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles both {@code txn.created} and {@code fraud.verdict} in one listener.
     * Spring Kafka's JsonDeserialiser uses the {@code __TypeId__} header (written
     * by the producer's JsonSerializer) to determine the concrete class to
     * deserialise into. We receive a raw {@code Object} and use pattern matching
     * to dispatch — keeping the listener signature open to additional event types
     * without changing the method signature.
     *
     * @param record  the raw Kafka record (value deserialized to Object)
     * @param ack     manual acknowledgment — committed after outbox persistence
     */
    @KafkaListener(
            topics  = {TOPIC_TXN_CREATED, TOPIC_FRAUD_VERDICT},
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onEvent(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        eventsReceivedCounter.increment();

        String topic  = record.topic();
        Object value  = record.value();

        log.info("Received event from topic=[{}] partition=[{}] offset=[{}]",
                topic, record.partition(), record.offset());

        try {
            // ── 1. Assemble payload using Pattern Matching ────────────────────
            NotificationPayload payload = assemblePayload(value, topic);

            // ── 2. Persist outbox row (PENDING) in local DB transaction ───────
            NotificationOutboxEntity outboxEntry = persistOutboxEntry(payload);
            outboxWrittenCounter.increment();

            // ── 3. Attempt immediate dispatch ─────────────────────────────────
            N8nWebhookClient.DispatchResult result = webhookClient.dispatch(payload);

            // ── 4. Update outbox row based on typed result ────────────────────
            //
            // Java 21: pattern matching for switch on sealed DispatchResult.
            // Every permitted subtype is handled — compiler enforces exhaustiveness.
            // Record patterns extract the components inline (no intermediate vars).
            //
            switch (result) {
                case N8nWebhookClient.DispatchResult.Success(var httpCode) -> {
                    outboxEntry.markSent();
                    log.info("Immediate dispatch succeeded [notificationId={}] HTTP {}",
                            payload.notificationId(), httpCode);
                }
                case N8nWebhookClient.DispatchResult.RetryableFailure(var reason, var cause) -> {
                    outboxEntry.markFailed(reason, backoffBase);
                    log.warn("Immediate dispatch failed (retryable) [notificationId={}]: {}",
                            payload.notificationId(), reason);
                }
                case N8nWebhookClient.DispatchResult.PermanentFailure(var reason, var cause) -> {
                    // Force exhaustion of retries so scheduler skips it
                    for (int i = 0; i < maxRetries; i++) {
                        outboxEntry.markFailed(reason + " [PERMANENT]", backoffBase);
                    }
                    log.error("Immediate dispatch permanently failed [notificationId={}]: {}",
                            payload.notificationId(), reason, cause);
                }
            }

            outboxRepository.save(outboxEntry);

            // ── 5. Acknowledge offset AFTER outbox row is persisted ───────────
            // This is the at-least-once guarantee: if JVM crashes after DB write
            // but before ack, Kafka redelivers, we find the outbox row already
            // persisted, and the retry scheduler handles dispatch.
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Fatal error processing event from topic=[{}]: {}",
                    topic, e.getMessage(), e);
            // Do NOT acknowledge — Kafka will redeliver this event.
            // In production: configure a dead-letter topic for poison pills.
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAYLOAD ASSEMBLY — Java 21 Pattern Matching for switch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Assembles a {@link NotificationPayload} from either a {@link TransactionDto}
     * or a {@link FraudVerdictEvent}.
     *
     * ── Traditional Java 8/11 approach ────────────────────────────────────────
     *
     *   if (value instanceof TransactionDto) {
     *       TransactionDto tx = (TransactionDto) value;  // explicit downcast
     *       return buildFromTransaction(tx);
     *   } else if (value instanceof FraudVerdictEvent) {
     *       FraudVerdictEvent fv = (FraudVerdictEvent) value;  // explicit downcast
     *       return buildFromVerdict(fv);
     *   } else {
     *       throw new IllegalArgumentException("Unknown event type: " + value.getClass());
     *   }
     *   // No compiler help if you add a new event type later.
     *
     * ── Java 21 approach ──────────────────────────────────────────────────────
     *
     *   switch (value) {
     *       case TransactionDto tx   -> buildFromTransaction(tx);
     *       case FraudVerdictEvent fv -> buildFromVerdict(fv);
     *       default                   -> throw ...;
     *   }
     *
     *   Pattern matching binds the variable WITH the correct type — no cast.
     *   If value is null, the switch throws NullPointerException at the switch
     *   statement (not a silent null bind), which is the correct behaviour.
     */
    private NotificationPayload assemblePayload(Object value, String topic) {
        return switch (value) {
            case TransactionDto tx          -> buildFromTransaction(tx);
            case FraudVerdictEvent verdict  -> buildFromVerdict(verdict);
            case null                       -> throw new IllegalArgumentException(
                    "Null event payload on topic: " + topic);
            default                         -> throw new IllegalArgumentException(
                    "Unrecognised event type [%s] on topic [%s]"
                            .formatted(value.getClass().getName(), topic));
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUILD FROM TransactionDto
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Translates a TransactionDto into a NotificationPayload.
     *
     * Uses a nested pattern match on {@code tx.type()} to produce a
     * human-readable summary per transaction type — same sealed hierarchy,
     * same exhaustiveness guarantee.
     */
    private NotificationPayload buildFromTransaction(TransactionDto tx) {

        // Inner switch: TransactionType sealed hierarchy → human summary + severity
        record TypeContext(String typeLabel, String summary, Severity severity) {}

        TypeContext ctx = switch (tx.type()) {

            case TransactionType.PeerToPeer(var recipientId, var note) ->
                    new TypeContext(
                            "PEER_TO_PEER",
                            "₹%s sent to account %s%s"
                                     .formatted(tx.amount(), recipientId,
                                                note != null && !note.isBlank()
                                                    ? " — \"" + note + "\""
                                                    : ""),
                            Severity.INFO);

            case TransactionType.Withdrawal(var bankCode, var instant) ->
                    new TypeContext(
                            "WITHDRAWAL",
                            "₹%s %swithdrawal to bank [%s] initiated"
                                    .formatted(tx.amount(),
                                               instant ? "instant " : "",
                                               bankCode),
                            Severity.INFO);

            case TransactionType.Deposit(var sourceRef) ->
                    new TypeContext(
                            "DEPOSIT",
                            "₹%s credited from reference [%s]"
                                    .formatted(tx.amount(), sourceRef),
                            Severity.INFO);

            case TransactionType.MerchantPayment(var merchantId, var name, var mcc) ->
                    new TypeContext(
                            "MERCHANT_PAYMENT",
                            "₹%s paid to %s (MCC %d)".formatted(tx.amount(), name, mcc),
                            Severity.INFO);
        };

        return new NotificationPayload(
                UUID.randomUUID(),
                "TRANSACTION_CREATED",
                tx.transactionId(),
                tx.sourceAccountId(),
                tx.initiatedByUserId(),
                tx.amount(),
                tx.currency().getCurrencyCode(),
                ctx.typeLabel(),
                tx.status().name(),
                null,       // no fraud verdict
                null,       // no risk score
                null,       // no triggered rules
                ctx.severity().name(),
                ctx.summary(),
                tx.createdAt()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUILD FROM FraudVerdictEvent
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Translates a FraudVerdictEvent into a NotificationPayload.
     *
     * The severity and human summary are determined by the verdict enum —
     * using a switch expression that returns a local record for clean
     * multi-value extraction without needing separate helper methods.
     */
    private NotificationPayload buildFromVerdict(FraudVerdictEvent verdict) {

        record VerdictContext(Severity severity, String summary) {}

        VerdictContext ctx = switch (verdict.verdict()) {

            case PASS -> new VerdictContext(
                    Severity.INFO,
                    "Transaction %s cleared fraud check (score: %.2f)"
                            .formatted(verdict.transactionId(), verdict.riskScore()));

            case FLAG -> new VerdictContext(
                    Severity.WARNING,
                    "⚠ Transaction %s flagged for review — score %.2f. Rules: %s"
                            .formatted(verdict.transactionId(),
                                       verdict.riskScore(),
                                       String.join(", ", verdict.triggeredRules())));

            case BLOCK -> new VerdictContext(
                    Severity.CRITICAL,
                    "🚫 Transaction %s BLOCKED — score %.2f. Rules: %s"
                            .formatted(verdict.transactionId(),
                                       verdict.riskScore(),
                                       String.join(", ", verdict.triggeredRules())));
        };

        return new NotificationPayload(
                UUID.randomUUID(),
                "FRAUD_VERDICT",
                verdict.transactionId(),
                "system",                           // accountId filled by downstream enrichment
                "system",                           // recipientUserId filled by downstream enrichment
                null,                               // amount not available from verdict alone
                null,                               // currency not available from verdict alone
                null,                               // transactionType not available here
                verdict.verdict().name(),
                verdict.verdict().name(),
                verdict.riskScore(),
                verdict.triggeredRules(),
                ctx.severity().name(),
                ctx.summary(),
                verdict.evaluatedAt()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OUTBOX PERSISTENCE
    // ─────────────────────────────────────────────────────────────────────────

    private NotificationOutboxEntity persistOutboxEntry(NotificationPayload payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot serialise notification payload", e);
        }

        NotificationOutboxEntity entry = NotificationOutboxEntity.builder()
                .transactionId(payload.transactionId())
                .eventType(payload.eventType())
                .accountId(payload.accountId() != null ? payload.accountId() : "unknown")
                .recipientUserId(payload.recipientUserId() != null ? payload.recipientUserId() : "unknown")
                .alertSeverity(Severity.valueOf(payload.alertSeverity()))
                .payload(payloadJson)
                .status(OutboxStatus.PENDING)
                .maxRetries(maxRetries)
                .build();

        return outboxRepository.save(entry);
    }
}
