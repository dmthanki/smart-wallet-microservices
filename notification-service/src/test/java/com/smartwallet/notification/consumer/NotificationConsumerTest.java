package com.smartwallet.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.enums.TransactionType;
import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.notification.client.N8nWebhookClient;
import com.smartwallet.notification.client.WebhookClient;
import com.smartwallet.notification.domain.NotificationPayload;
import com.smartwallet.notification.outbox.OutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationConsumer — payload assembly and dispatch routing")
class NotificationConsumerTest {

    @Mock private OutboxRepository    outboxRepository;
    @Mock private WebhookClient       webhookClient;
    @Mock private Acknowledgment      ack;

    private NotificationConsumer consumer;
    private ObjectMapper          objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        consumer = new NotificationConsumer(outboxRepository, webhookClient, objectMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "maxRetries", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "backoffBase", 10L);

        // Default: webhook succeeds
        lenient().when(webhookClient.dispatch(any()))
                .thenReturn(new N8nWebhookClient.DispatchResult.Success(200));

        // Outbox save returns the passed entity
        lenient().when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransactionDto events
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("txn.created — TransactionDto events")
    class TransactionCreatedEvents {

        @Test
        @DisplayName("P2P transaction produces INFO severity payload with correct summary")
        void p2pTransaction_infoSeverity() {
            var tx = TransactionDto.of(
                    UUID.randomUUID().toString(), "acc-src",
                    new BigDecimal("5000"), "INR",
                    new TransactionType.PeerToPeer("acc-dst", "lunch"),
                    "user-001");

            consumer.onEvent(kafkaRecord("txn.created", tx), ack);

            var payloadCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
            verify(webhookClient).dispatch(payloadCaptor.capture());

            NotificationPayload payload = payloadCaptor.getValue();
            assertThat(payload.eventType()).isEqualTo("TRANSACTION_CREATED");
            assertThat(payload.transactionType()).isEqualTo("PEER_TO_PEER");
            assertThat(payload.alertSeverity()).isEqualTo("INFO");
            assertThat(payload.humanSummary()).contains("acc-dst");
            assertThat(payload.humanSummary()).contains("lunch");
            assertThat(payload.fraudVerdict()).isNull();
            assertThat(payload.riskScore()).isNull();

            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("Instant withdrawal produces INFO payload with 'instant' in summary")
        void instantWithdrawal_summaryContainsInstant() {
            var tx = TransactionDto.of(
                    UUID.randomUUID().toString(), "acc-src",
                    new BigDecimal("20000"), "INR",
                    new TransactionType.Withdrawal("HDFCINBB", true),
                    "user-002");

            consumer.onEvent(kafkaRecord("txn.created", tx), ack);

            var captor = ArgumentCaptor.forClass(NotificationPayload.class);
            verify(webhookClient).dispatch(captor.capture());

            assertThat(captor.getValue().humanSummary()).contains("instant");
            assertThat(captor.getValue().transactionType()).isEqualTo("WITHDRAWAL");
        }

        @Test
        @DisplayName("Merchant payment to gambling MCC produces INFO payload")
        void merchantPayment_gamblingMcc_infoPaylod() {
            var tx = TransactionDto.of(
                    UUID.randomUUID().toString(), "acc-src",
                    new BigDecimal("10000"), "INR",
                    new TransactionType.MerchantPayment("m-001", "Lucky Casino", 7995),
                    "user-003");

            consumer.onEvent(kafkaRecord("txn.created", tx), ack);

            var captor = ArgumentCaptor.forClass(NotificationPayload.class);
            verify(webhookClient).dispatch(captor.capture());

            assertThat(captor.getValue().alertSeverity()).isEqualTo("INFO");
            assertThat(captor.getValue().humanSummary()).contains("MCC 7995");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FraudVerdictEvent events
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fraud.verdict — FraudVerdictEvent events")
    class FraudVerdictEvents {

        @Test
        @DisplayName("PASS verdict produces INFO payload")
        void passVerdict_infoSeverity() {
            var verdict = FraudVerdictEvent.pass(UUID.randomUUID());

            consumer.onEvent(kafkaRecord("fraud.verdict", verdict), ack);

            var captor = ArgumentCaptor.forClass(NotificationPayload.class);
            verify(webhookClient).dispatch(captor.capture());

            NotificationPayload payload = captor.getValue();
            assertThat(payload.eventType()).isEqualTo("FRAUD_VERDICT");
            assertThat(payload.fraudVerdict()).isEqualTo("PASS");
            assertThat(payload.alertSeverity()).isEqualTo("INFO");
            assertThat(payload.riskScore()).isZero();
            assertThat(payload.triggeredRules()).isEmpty();
        }

        @Test
        @DisplayName("FLAG verdict produces WARNING payload with rule names in summary")
        void flagVerdict_warningSeverity() {
            var txId    = UUID.randomUUID();
            var verdict = new FraudVerdictEvent(
                    UUID.randomUUID(), txId,
                    FraudVerdictEvent.Verdict.FLAG,
                    0.60,
                    List.of("HIGH_VALUE_P2P", "OFF_HOURS"),
                    "Flagged for review",
                    Instant.now());

            consumer.onEvent(kafkaRecord("fraud.verdict", verdict), ack);

            var captor = ArgumentCaptor.forClass(NotificationPayload.class);
            verify(webhookClient).dispatch(captor.capture());

            NotificationPayload payload = captor.getValue();
            assertThat(payload.alertSeverity()).isEqualTo("WARNING");
            assertThat(payload.fraudVerdict()).isEqualTo("FLAG");
            assertThat(payload.riskScore()).isEqualTo(0.60);
            assertThat(payload.triggeredRules()).containsExactly("HIGH_VALUE_P2P", "OFF_HOURS");
            assertThat(payload.humanSummary()).contains("⚠");
            assertThat(payload.humanSummary()).contains("HIGH_VALUE_P2P");
        }

        @Test
        @DisplayName("BLOCK verdict produces CRITICAL payload with blocking emoji in summary")
        void blockVerdict_criticalSeverity() {
            var txId    = UUID.randomUUID();
            var verdict = FraudVerdictEvent.block(
                    txId, 0.95, List.of("VELOCITY_BREACH_PER_MINUTE", "SELF_TRANSFER_ATTEMPT"),
                    "Blocked by velocity + self-transfer rules");

            consumer.onEvent(kafkaRecord("fraud.verdict", verdict), ack);

            var captor = ArgumentCaptor.forClass(NotificationPayload.class);
            verify(webhookClient).dispatch(captor.capture());

            NotificationPayload payload = captor.getValue();
            assertThat(payload.alertSeverity()).isEqualTo("CRITICAL");
            assertThat(payload.fraudVerdict()).isEqualTo("BLOCK");
            assertThat(payload.humanSummary()).contains("🚫");
            assertThat(payload.humanSummary()).contains("BLOCKED");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dispatch result routing
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Dispatch result → outbox status routing")
    class DispatchResultRouting {

        @Test
        @DisplayName("RetryableFailure → ack still committed, outbox marked FAILED")
        void retryableFailure_acksAndMarksFailed() {
            when(webhookClient.dispatch(any()))
                    .thenReturn(new N8nWebhookClient.DispatchResult.RetryableFailure(
                            "503 Service Unavailable", null));

            var tx = simpleDeposit();
            consumer.onEvent(kafkaRecord("txn.created", tx), ack);

            // Offset must still be acknowledged — outbox handles retries
            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("PermanentFailure → ack committed, outbox marked DEAD after one tick")
        void permanentFailure_acksAndMarksDead() {
            when(webhookClient.dispatch(any()))
                    .thenReturn(new N8nWebhookClient.DispatchResult.PermanentFailure(
                            "401 Unauthorized", null));

            var tx = simpleDeposit();
            consumer.onEvent(kafkaRecord("txn.created", tx), ack);

            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("Exception during processing → offset NOT acknowledged")
        void exceptionDuringProcessing_noAck() {
            when(webhookClient.dispatch(any())).thenThrow(new RuntimeException("unexpected"));

            var tx = simpleDeposit();
            consumer.onEvent(kafkaRecord("txn.created", tx), ack);

            // No ack — Kafka will redeliver
            verify(ack, never()).acknowledge();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private <T> ConsumerRecord<String, Object> kafkaRecord(String topic, T value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }

    private TransactionDto simpleDeposit() {
        return TransactionDto.of(
                UUID.randomUUID().toString(), "acc-src",
                new BigDecimal("1000"), "INR",
                new TransactionType.Deposit("bank-ref-001"),
                "user-001");
    }
}
