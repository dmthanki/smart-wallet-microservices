package com.smartwallet.fraud.rules;

import com.smartwallet.common.dto.TransactionDto;

/**
 * Sealed interface for the Chain of Responsibility rule pattern.
 *
 * JAVA 21 — Sealed interfaces here serve two purposes:
 *   1. They document the closed set of rule categories at compile time.
 *   2. They allow pattern matching in the engine if we ever need to
 *      dispatch differently per category (e.g., skip VELOCITY rules for
 *      deposits without an if-else chain).
 *
 * Each concrete rule is a Spring @Component and gets injected into
 * FraudRuleEngine as a List<FraudRule> — Spring collects all beans
 * implementing the interface automatically.
 *
 * RuleResult is a RECORD — a natural fit because it is a pure data
 * carrier: triggered + ruleName + scoreContribution.
 */
public sealed interface FraudRule
        permits VelocityRule, AmountThresholdRule, GeoAnomalyRule, OffHoursRule {

    /**
     * Evaluate the rule against the given transaction.
     * @return a RuleResult record (never null — use RuleResult.pass() for no-op)
     */
    RuleResult evaluate(TransactionDto tx);

    /**
     * Human-readable rule identifier, used in fraud_logs.triggered_rules JSONB column.
     */
    String ruleName();

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * TRADITIONAL: a mutable POJO with isTriggered(), getScore(), getName() getters.
     * JAVA 21: a record — three components, compiler-generated accessors, equals, hashCode.
     * Compact canonical constructor validates the score range.
     */
    record RuleResult(boolean triggered, String ruleName, double scoreContribution) {

        public RuleResult {
            if (scoreContribution < 0.0 || scoreContribution > 1.0) {
                throw new IllegalArgumentException(
                        "scoreContribution must be in [0.0, 1.0], got: " + scoreContribution);
            }
        }

        public static RuleResult pass(String ruleName) {
            return new RuleResult(false, ruleName, 0.0);
        }

        public static RuleResult trigger(String ruleName, double score) {
            return new RuleResult(true, ruleName, score);
        }
    }
}
