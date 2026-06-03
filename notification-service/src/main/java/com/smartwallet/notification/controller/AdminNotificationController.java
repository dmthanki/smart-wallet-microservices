package com.smartwallet.notification.controller;

import com.smartwallet.notification.outbox.NotificationOutboxEntity;
import com.smartwallet.notification.outbox.OutboxRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only admin API for outbox observability.
 *
 * Exposes:
 *   GET /api/v1/notifications/outbox/stats       — count by status
 *   GET /api/v1/notifications/outbox/dead        — all DEAD entries
 *   GET /api/v1/notifications/outbox/{txId}      — outbox rows for a transaction
 *
 * In production: secure behind an internal-only ingress rule or
 * Spring Security's hasRole("ADMIN") expression.
 *
 * Java 21 — response types use records for clean ad-hoc projection:
 * No need to create a separate class for a one-off stats response —
 * a local record at the method level is enough.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class AdminNotificationController {

    private final OutboxRepository outboxRepository;

    public AdminNotificationController(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /** Outbox depth broken down by status — used by Grafana dashboards. */
    @GetMapping("/outbox/stats")
    public ResponseEntity<Map<String, Long>> outboxStats() {

        // Java 21: Map.of() with each status as a key — no loop or stream needed
        // for a small, fixed set of enum values.
        Map<String, Long> stats = Map.of(
                "pending",  outboxRepository.countByStatus(NotificationOutboxEntity.OutboxStatus.PENDING),
                "retrying", outboxRepository.countByStatus(NotificationOutboxEntity.OutboxStatus.RETRYING),
                "sent",     outboxRepository.countByStatus(NotificationOutboxEntity.OutboxStatus.SENT),
                "failed",   outboxRepository.countByStatus(NotificationOutboxEntity.OutboxStatus.FAILED),
                "dead",     outboxRepository.countByStatus(NotificationOutboxEntity.OutboxStatus.DEAD)
        );

        return ResponseEntity.ok(stats);
    }

    /** All permanently failed outbox entries requiring manual investigation. */
    @GetMapping("/outbox/dead")
    public ResponseEntity<List<OutboxSummary>> deadLetters() {
        List<OutboxSummary> dead = outboxRepository
                .findAll()
                .stream()
                .filter(e -> e.getStatus() == NotificationOutboxEntity.OutboxStatus.DEAD)
                // Java 21: record projection — maps entity to a concise summary record
                .map(e -> new OutboxSummary(
                        e.getId(),
                        e.getTransactionId(),
                        e.getEventType(),
                        e.getAlertSeverity().name(),
                        e.getRetryCount(),
                        e.getLastError(),
                        e.getLastAttemptedAt() != null ? e.getLastAttemptedAt().toString() : null))
                .toList();

        return ResponseEntity.ok(dead);
    }

    /** Outbox history for a specific transaction — useful for support investigations. */
    @GetMapping("/outbox/{transactionId}")
    public ResponseEntity<List<OutboxSummary>> byTransaction(
            @PathVariable UUID transactionId) {

        List<OutboxSummary> entries = outboxRepository
                .findByTransactionId(transactionId)
                .stream()
                .map(e -> new OutboxSummary(
                        e.getId(),
                        e.getTransactionId(),
                        e.getEventType(),
                        e.getAlertSeverity().name(),
                        e.getRetryCount(),
                        e.getLastError(),
                        e.getLastAttemptedAt() != null ? e.getLastAttemptedAt().toString() : null))
                .toList();

        return entries.isEmpty()
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(entries);
    }

    // ── Local response record — Java 21 ──────────────────────────────────────
    // Defined at the enclosing class level so both methods can share it.
    // Traditional Java would require a separate file or a static inner class.
    public record OutboxSummary(
            UUID   id,
            UUID   transactionId,
            String eventType,
            String alertSeverity,
            int    retryCount,
            String lastError,
            String lastAttemptedAt
    ) {}
}
