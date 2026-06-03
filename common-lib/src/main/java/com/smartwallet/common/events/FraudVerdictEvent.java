package com.smartwallet.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain event published by FraudDetectionService onto the
 * {@code fraud.verdict} Kafka topic. Consumed by TransactionService and
 * NotificationService.
 *
 * JAVA 21 Records as event payloads:
 * ─────────────────────────────────
 * Records are ideal for Kafka message payloads because:
 *   1. Serialization frameworks (Jackson, Avro) handle them natively.
 *   2. Immutability guarantees the payload cannot be mutated after publishing.
 *   3. The compiler-generated equals/hashCode makes deduplication trivial.
 *   4. Component accessors (no "get" prefix) produce cleaner JSON field names
 *      when combined with Jackson's @JsonProperty or RecordNamingStrategy.
 *
 * TRADITIONAL Java 8/11:
 *   A mutable POJO with @JsonProperty annotations, getters, setters, and a
 *   no-arg constructor required by Jackson's default ObjectMapper. You had to
 *   manually ensure immutability by making fields final and omitting setters,
 *   but Jackson's default deserializer then required @JsonCreator — more
 *   boilerplate. Records solve this cleanly with Jackson 2.12+.
 */
public record FraudVerdictEvent(
        UUID eventId,
        UUID transactionId,
        Verdict verdict,
        double riskScore,           // 0.0 – 1.0
        List<String> triggeredRules,
        String analysisSummary,
        Instant evaluatedAt
) {

    /**
     * Compact canonical constructor normalises risk score to [0.0, 1.0].
     * Note: List.copyOf() makes triggeredRules truly immutable — defensive copy
     * at construction time means callers cannot mutate the list via the reference
     * they passed in.
     */
    public FraudVerdictEvent {
        if (riskScore < 0.0 || riskScore > 1.0) {
            throw new IllegalArgumentException(
                    "riskScore must be in [0.0, 1.0], got: " + riskScore);
        }
        triggeredRules = List.copyOf(triggeredRules); // defensive immutable copy
    }

    public enum Verdict { PASS, FLAG, BLOCK }

    // ── Convenience factories ─────────────────────────────────────────────────

    public static FraudVerdictEvent pass(UUID transactionId) {
        return new FraudVerdictEvent(
                UUID.randomUUID(), transactionId,
                Verdict.PASS, 0.0, List.of(), "No rules triggered",
                Instant.now());
    }

    public static FraudVerdictEvent block(
            UUID transactionId,
            double riskScore,
            List<String> rules,
            String summary) {
        return new FraudVerdictEvent(
                UUID.randomUUID(), transactionId,
                Verdict.BLOCK, riskScore, rules, summary,
                Instant.now());
    }

    public boolean isBlocked() { return verdict == Verdict.BLOCK; }
    public boolean isFlagged() { return verdict == Verdict.FLAG;  }
}
