package com.smartwallet.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwallet.notification.client.N8nWebhookClient;
import com.smartwallet.notification.client.WebhookClient;
import com.smartwallet.notification.domain.NotificationPayload;
import com.smartwallet.notification.outbox.NotificationOutboxEntity;
import com.smartwallet.notification.outbox.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.SequencedCollection;

/**
 * Scheduled retry processor for the notification outbox.
 *
 * ── Reliability model ──────────────────────────────────────────────────────
 *   The NotificationConsumer writes outbox rows and attempts an immediate
 *   webhook dispatch. This scheduler handles rows where that first attempt
 *   failed, providing at-least-once delivery with exponential back-off.
 *
 * ── SequencedCollection usage ──────────────────────────────────────────────
 *   The scheduler fetches a List from the DB, loads it into an ArrayDeque
 *   (which implements SequencedCollection), and processes it as a queue:
 *
 *     • pollFirst()  — dequeue oldest-due entry for processing
 *     • getFirst()   — peek at the next entry without removing
 *     • size()       — remaining count for progress logging
 *     • reversed()   — if we ever need to process newest-first (priority inversion)
 *
 *   The SequencedCollection type gives us a stable, unified API across
 *   the dequeue operations. Traditional Java would use List with index
 *   arithmetic, or raw Deque without a common supertype.
 *
 * ── Horizontal scaling ─────────────────────────────────────────────────────
 *   The DB query uses FOR UPDATE SKIP LOCKED — multiple instances of the
 *   notification-service can run simultaneously without double-processing
 *   the same outbox row.
 *
 * ── Stale RETRYING guard ───────────────────────────────────────────────────
 *   Each tick first resets records stuck in RETRYING for > 2 minutes back
 *   to FAILED. This guards against rows orphaned by a JVM crash mid-batch.
 */
@Component
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxRepository  outboxRepository;
    private final WebhookClient     webhookClient;
    private final ObjectMapper      objectMapper;
    private final Counter           retrySuccessCounter;
    private final Counter           retryFailureCounter;
    private final Counter           deadLetterCounter;

    @Value("${notification.outbox.batch-size:20}")
    private int batchSize;

    @Value("${notification.outbox.backoff-base-seconds:10}")
    private long backoffBase;

    @Value("${notification.outbox.max-retries:5}")
    private int maxRetries;

    public OutboxScheduler(
            OutboxRepository outboxRepository,
            WebhookClient webhookClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {

        this.outboxRepository  = outboxRepository;
        this.webhookClient     = webhookClient;
        this.objectMapper      = objectMapper;

        this.retrySuccessCounter = Counter.builder("notification.outbox.retry.success")
                .description("Outbox retries that succeeded")
                .register(meterRegistry);
        this.retryFailureCounter = Counter.builder("notification.outbox.retry.failure")
                .description("Outbox retries that failed again")
                .register(meterRegistry);
        this.deadLetterCounter   = Counter.builder("notification.outbox.dead")
                .description("Outbox entries moved to DEAD status")
                .register(meterRegistry);

        // Live gauge — shows pending outbox depth in Prometheus / Grafana
        Gauge.builder("notification.outbox.pending.count",
                outboxRepository,
                repo -> repo.countByStatus(NotificationOutboxEntity.OutboxStatus.PENDING)
                          + repo.countByStatus(NotificationOutboxEntity.OutboxStatus.FAILED))
                .description("Pending + failed notification outbox entries")
                .register(meterRegistry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN SCHEDULER TICK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs every {@code notification.outbox.retry-interval-ms} milliseconds.
     * Default: every 30 seconds.
     *
     * fixedDelayString ensures ticks do not overlap — the next tick starts
     * only AFTER the previous one completes, preventing concurrent batch loads.
     */
    @Scheduled(fixedDelayString = "${notification.outbox.retry-interval-ms:30000}")
    @Transactional
    public void processPendingOutbox() {
        log.debug("Outbox scheduler tick started");
        Instant tickStart = Instant.now();

        // ── 1. Reset stale RETRYING rows ──────────────────────────────────────
        Instant staleThreshold = Instant.now().minus(Duration.ofMinutes(2));
        int resetCount = outboxRepository.resetStaleRetrying(staleThreshold);
        if (resetCount > 0) {
            log.warn("Reset {} stale RETRYING outbox records back to FAILED", resetCount);
        }

        // ── 2. Fetch retryable batch from DB ──────────────────────────────────
        List<NotificationOutboxEntity> rawBatch =
                outboxRepository.findRetryableBatch(Instant.now(), batchSize);

        if (rawBatch.isEmpty()) {
            log.debug("No retryable outbox entries found");
            return;
        }

        // ── 3. Load into SequencedCollection (ArrayDeque) ─────────────────────
        SequencedCollection<NotificationOutboxEntity> queue =
                new ArrayDeque<>(rawBatch);

        log.info("Outbox scheduler processing {} entries (of max batchSize={})",
                queue.size(), batchSize);

        int successCount = 0;
        int failCount    = 0;
        int deadCount    = 0;

        // ── 4. Process queue entries ──────────────────────────────────────────
        while (!queue.isEmpty()) {

            // pollFirst() — remove and return the head (oldest-due entry)
            NotificationOutboxEntity entry = ((ArrayDeque<NotificationOutboxEntity>) queue).pollFirst();

            if (entry == null || !entry.isRetryable()) continue;

            entry.markRetrying();
            outboxRepository.save(entry);

            // Log progress using the SequencedCollection's size (remaining)
            log.debug("Retrying outbox [id={}] attempt={}/{}, remaining in batch={}",
                    entry.getId(), entry.getRetryCount() + 1, entry.getMaxRetries(), queue.size());

            // ── 5. Deserialise stored payload and dispatch ────────────────────
            NotificationPayload payload = deserialisePayload(entry);
            if (payload == null) {
                entry.markFailed("Payload deserialisation failed — skipping", backoffBase);
                outboxRepository.save(entry);
                failCount++;
                continue;
            }

            N8nWebhookClient.DispatchResult result = webhookClient.dispatch(payload);

            // ── 6. Pattern match on sealed DispatchResult ─────────────────────
            switch (result) {

                case N8nWebhookClient.DispatchResult.Success(var httpCode) -> {
                    entry.markSent();
                    retrySuccessCounter.increment();
                    successCount++;
                    log.info("Retry succeeded [outboxId={}] HTTP {}", entry.getId(), httpCode);
                }

                case N8nWebhookClient.DispatchResult.RetryableFailure(var reason, var cause) -> {
                    entry.markFailed(reason, backoffBase);
                    retryFailureCounter.increment();
                    failCount++;

                    if (entry.getStatus() == NotificationOutboxEntity.OutboxStatus.DEAD) {
                        deadLetterCounter.increment();
                        deadCount++;
                        log.error("Outbox entry [id={}] moved to DEAD after {} attempts. Last error: {}",
                                entry.getId(), entry.getRetryCount(), reason);
                    } else {
                        log.warn("Retry failed (retryable) [outboxId={}] attempt={}, nextRetry={}: {}",
                                entry.getId(), entry.getRetryCount(),
                                entry.getNextRetryAt(), reason);
                    }
                }

                case N8nWebhookClient.DispatchResult.PermanentFailure(var reason, var cause) -> {
                    // Exhaust retries immediately — do not keep retrying a permanent failure
                    for (int i = entry.getRetryCount(); i < entry.getMaxRetries(); i++) {
                        entry.markFailed(reason + " [PERMANENT]", backoffBase);
                    }
                    deadLetterCounter.increment();
                    retryFailureCounter.increment();
                    deadCount++;
                    failCount++;
                    log.error("Outbox entry [id={}] permanently failed: {}", entry.getId(), reason, cause);
                }
            }

            outboxRepository.save(entry);
        }

        Duration elapsed = Duration.between(tickStart, Instant.now());
        log.info("Outbox scheduler tick complete in {}ms — success={}, failed={}, dead={}",
                elapsed.toMillis(), successCount, failCount, deadCount);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DEAD LETTER REPORT — runs every hour
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Logs a summary of DEAD outbox entries every hour.
     */
    @Scheduled(fixedDelay = 3_600_000) // 1 hour
    public void reportDeadLetters() {
        long deadCount = outboxRepository.countByStatus(NotificationOutboxEntity.OutboxStatus.DEAD);
        if (deadCount > 0) {
            log.error("DEAD LETTER ALERT: {} notification outbox entries need manual investigation",
                    deadCount);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private NotificationPayload deserialisePayload(NotificationOutboxEntity entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), NotificationPayload.class);
        } catch (Exception e) {
            log.error("Cannot deserialise payload for outbox [id={}]: {}",
                    entry.getId(), e.getMessage());
            return null;
        }
    }
}
