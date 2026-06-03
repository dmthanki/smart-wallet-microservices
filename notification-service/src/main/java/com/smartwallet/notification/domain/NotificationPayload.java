package com.smartwallet.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Canonical notification payload sent to the n8n webhook.
 *
 * This record is the Anti-Corruption Layer between the internal domain events
 * (TransactionDto, FraudVerdictEvent) and the external n8n workflow contract.
 * n8n sees a single, stable JSON structure regardless of which internal event
 * produced it — insulating the workflow from internal schema changes.
 *
 * Fields are nullable where they are event-specific (e.g. riskScore and
 * triggeredRules only populate for fraud.verdict events).
 */
public record NotificationPayload(
        UUID            notificationId,
        String          eventType,          // "TRANSACTION_CREATED" | "FRAUD_VERDICT"
        UUID            transactionId,
        String          accountId,
        String          recipientUserId,
        BigDecimal      amount,
        String          currency,
        String          transactionType,    // "PEER_TO_PEER" | "WITHDRAWAL" etc.
        String          transactionStatus,
        String          fraudVerdict,       // null for txn.created events
        Double          riskScore,          // null for txn.created events
        List<String>    triggeredRules,     // empty for txn.created events
        String          alertSeverity,      // "INFO" | "WARNING" | "CRITICAL"
        String          humanSummary,       // pre-rendered message for n8n template
        Instant         occurredAt
) {
    public NotificationPayload {
        triggeredRules = triggeredRules == null ? List.of() : List.copyOf(triggeredRules);
    }

    /** Severity levels used by the n8n workflow to route to the correct channel. */
    public enum Severity {
        INFO,       // routine transaction confirmed
        WARNING,    // flagged for review, transaction still processed
        CRITICAL    // transaction blocked
    }
}
