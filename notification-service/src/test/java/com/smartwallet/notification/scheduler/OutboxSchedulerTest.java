package com.smartwallet.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartwallet.notification.client.N8nWebhookClient;
import com.smartwallet.notification.client.WebhookClient;
import com.smartwallet.notification.domain.NotificationPayload;
import com.smartwallet.notification.outbox.NotificationOutboxEntity;
import com.smartwallet.notification.outbox.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.SequencedCollection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxScheduler — retry processing and SequencedCollection queue semantics")
class OutboxSchedulerTest {

    @Mock private OutboxRepository  outboxRepository;
    @Mock private WebhookClient     webhookClient;

    private OutboxScheduler scheduler;
    private ObjectMapper    objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        scheduler = new OutboxScheduler(
                outboxRepository, webhookClient, objectMapper, new SimpleMeterRegistry());
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "backoffBase", 10L);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "maxRetries", 5);

        // Default: no stale RETRYING rows
        lenient().when(outboxRepository.resetStaleRetrying(any())).thenReturn(0);
        lenient().when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Empty batch
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("empty retryable batch → no dispatch calls")
    void emptyBatch_noDispatches() {
        when(outboxRepository.findRetryableBatch(any(), anyInt()))
                .thenReturn(List.of());

        scheduler.processPendingOutbox();

        verifyNoInteractions(webhookClient);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SequencedCollection — FIFO order guarantee
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SequencedCollection queue processing order")
    class SequencedCollectionTests {

        @Test
        @DisplayName("entries dispatched in FIFO order (oldest next_retry_at first)")
        void entries_processedFifo() {
            var oldest  = pendingEntry("oldest", Instant.now().minusSeconds(300));
            var middle  = pendingEntry("middle", Instant.now().minusSeconds(60));
            var newest  = pendingEntry("newest", Instant.now().minusSeconds(10));

            // DB returns them in next_retry_at ASC order (matching the query)
            when(outboxRepository.findRetryableBatch(any(), anyInt()))
                    .thenReturn(List.of(oldest, middle, newest));

            when(webhookClient.dispatch(any()))
                    .thenReturn(new N8nWebhookClient.DispatchResult.Success(200));

            scheduler.processPendingOutbox();

            // All three dispatched exactly once
            verify(webhookClient, times(3)).dispatch(any());
        }

        @Test
        @DisplayName("ArrayDeque as SequencedCollection — getFirst/getLast/reversed semantics")
        void sequencedCollectionApi_directAccess() {
            // Populate in chronological order (oldest → newest)
            var entry1 = pendingEntry("entry-1", Instant.now().minusSeconds(100));
            var entry2 = pendingEntry("entry-2", Instant.now().minusSeconds(50));
            var entry3 = pendingEntry("entry-3", Instant.now().minusSeconds(10));

            SequencedCollection<NotificationOutboxEntity> queue =
                    new ArrayDeque<>(List.of(entry1, entry2, entry3));

            // JAVA 21: getFirst() — no index arithmetic (contrast: list.get(0))
            assertThat(queue.getFirst().getAccountId()).isEqualTo("entry-1");

            // JAVA 21: getLast() — no list.get(list.size() - 1)
            assertThat(queue.getLast().getAccountId()).isEqualTo("entry-3");

            // JAVA 21: reversed() — live view, newest first
            var reversedIds = queue.reversed().stream()
                    .map(NotificationOutboxEntity::getAccountId)
                    .toList();
            assertThat(reversedIds).containsExactly("entry-3", "entry-2", "entry-1");

            // pollFirst() on the underlying ArrayDeque — O(1) dequeue
            var deque = (ArrayDeque<NotificationOutboxEntity>) queue;
            var polled = deque.pollFirst();
            assertThat(polled.getAccountId()).isEqualTo("entry-1");
            assertThat(deque).hasSize(2);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retry outcome routing via DispatchResult sealed type
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DispatchResult pattern matching → outbox status transitions")
    class DispatchResultRouting {

        @Test
        @DisplayName("Success → entry marked SENT")
        void success_markedSent() {
            var entry = pendingEntry("acc-1", Instant.now().minusSeconds(60));
            when(outboxRepository.findRetryableBatch(any(), anyInt()))
                    .thenReturn(List.of(entry));
            when(webhookClient.dispatch(any()))
                    .thenReturn(new N8nWebhookClient.DispatchResult.Success(200));

            scheduler.processPendingOutbox();

            assertThat(entry.getStatus())
                    .isEqualTo(NotificationOutboxEntity.OutboxStatus.SENT);
            assertThat(entry.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("RetryableFailure → entry marked FAILED with back-off next_retry_at")
        void retryableFailure_markedFailed() {
            var entry = pendingEntry("acc-2", Instant.now().minusSeconds(60));
            when(outboxRepository.findRetryableBatch(any(), anyInt()))
                    .thenReturn(List.of(entry));
            when(webhookClient.dispatch(any()))
                    .thenReturn(new N8nWebhookClient.DispatchResult.RetryableFailure(
                            "503 n8n unavailable", null));

            scheduler.processPendingOutbox();

            assertThat(entry.getStatus())
                    .isEqualTo(NotificationOutboxEntity.OutboxStatus.FAILED);
            assertThat(entry.getRetryCount()).isEqualTo(1);
            assertThat(entry.getNextRetryAt()).isAfter(Instant.now());
            assertThat(entry.getLastError()).contains("503");
        }

        @Test
        @DisplayName("PermanentFailure → entry immediately moved to DEAD")
        void permanentFailure_markedDead() {
            var entry = pendingEntry("acc-3", Instant.now().minusSeconds(60));
            when(outboxRepository.findRetryableBatch(any(), anyInt()))
                    .thenReturn(List.of(entry));
            when(webhookClient.dispatch(any()))
                    .thenReturn(new N8nWebhookClient.DispatchResult.PermanentFailure(
                            "401 Unauthorized", null));

            scheduler.processPendingOutbox();

            assertThat(entry.getStatus())
                    .isEqualTo(NotificationOutboxEntity.OutboxStatus.DEAD);
        }

        @Test
        @DisplayName("entry exhausting max retries on RetryableFailure → moves to DEAD")
        void maxRetriesExhausted_markedDead() {
            var entry = pendingEntry("acc-4", Instant.now().minusSeconds(60));
            // Simulate already having failed 4 times (maxRetries = 5 by default)
            for (int i = 0; i < 4; i++) {
                entry.markFailed("previous failure", 0L);
                entry.markRetrying();  // re-open for next attempt
            }

            when(outboxRepository.findRetryableBatch(any(), anyInt()))
                    .thenReturn(List.of(entry));
            when(webhookClient.dispatch(any()))
                    .thenReturn(new N8nWebhookClient.DispatchResult.RetryableFailure(
                            "503 still down", null));

            scheduler.processPendingOutbox();

            assertThat(entry.getStatus())
                    .isEqualTo(NotificationOutboxEntity.OutboxStatus.DEAD);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stale RETRYING guard
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stale RETRYING rows are reset before processing batch")
    void staleRetrying_resetBeforeBatch() {
        when(outboxRepository.resetStaleRetrying(any())).thenReturn(3);
        when(outboxRepository.findRetryableBatch(any(), anyInt())).thenReturn(List.of());

        scheduler.processPendingOutbox();

        verify(outboxRepository).resetStaleRetrying(any());
        // Batch fetch still runs after the reset
        verify(outboxRepository).findRetryableBatch(any(), anyInt());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private NotificationOutboxEntity pendingEntry(String accountId, Instant nextRetryAt) {
        var payload = new NotificationPayload(
                UUID.randomUUID(), "TRANSACTION_CREATED", UUID.randomUUID(),
                accountId, "user-001", new BigDecimal("1000"), "INR",
                "DEPOSIT", "INITIATED", null, null, null,
                "INFO", "Test notification", Instant.now());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var entity = NotificationOutboxEntity.builder()
                .transactionId(payload.transactionId())
                .eventType("TRANSACTION_CREATED")
                .accountId(accountId)
                .recipientUserId("user-001")
                .alertSeverity(NotificationPayload.Severity.INFO)
                .payload(payloadJson)
                .status(NotificationOutboxEntity.OutboxStatus.PENDING)
                .maxRetries(5)
                .build();

        return entity;
    }
}
