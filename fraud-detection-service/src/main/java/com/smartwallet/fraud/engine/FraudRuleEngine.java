package com.smartwallet.fraud.engine;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.common.events.FraudVerdictEvent.Verdict;
import com.smartwallet.fraud.rules.FraudRule;
import com.smartwallet.fraud.rules.FraudRule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core fraud evaluation engine.
 *
 * Implements the Chain of Responsibility / Rules pattern using Java 21 features:
 *   - Orchestrates a collection of custom FraudRule components.
 *   - Aggregates scores and builds the final FraudVerdictEvent.
 */
@Component
public class FraudRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(FraudRuleEngine.class);

    private static final double BLOCK_THRESHOLD = 0.80;
    private static final double FLAG_THRESHOLD  = 0.40;

    private final List<FraudRule> rules;

    public FraudRuleEngine(List<FraudRule> rules) {
        this.rules = rules;
    }

    /**
     * Evaluates a TransactionDto across all registered fraud rules,
     * aggregates the risk scores, and emits a final FraudVerdictEvent.
     */
    public FraudVerdictEvent evaluate(TransactionDto tx) {
        log.info("Evaluating fraud risk for txn [{}]", tx.transactionId());

        // Evaluate all rules
        List<RuleResult> results = rules.stream()
                .map(rule -> rule.evaluate(tx))
                .toList();

        // Filter and collect triggered rules (splitting by comma for composite rules)
        List<String> triggeredRuleNames = results.stream()
                .filter(RuleResult::triggered)
                .flatMap(r -> java.util.Arrays.stream(r.ruleName().split(",")))
                .map(String::trim)
                .toList();

        // Sum the risk score contributions, clamped at 1.0 maximum
        double totalScore = results.stream()
                .filter(RuleResult::triggered)
                .mapToDouble(RuleResult::scoreContribution)
                .sum();
        totalScore = Math.min(totalScore, 1.0);

        // Determine the final verdict based on score thresholds
        Verdict verdict = Verdict.PASS;
        if (totalScore >= BLOCK_THRESHOLD) {
            verdict = Verdict.BLOCK;
        } else if (totalScore >= FLAG_THRESHOLD) {
            verdict = Verdict.FLAG;
        }

        String summary = "Score: %.2f | Verdict: %s | Rules: %s"
                .formatted(totalScore, verdict, triggeredRuleNames);

        log.info("Fraud evaluation complete for txn [{}]: score={}, verdict={}, rules={}",
                tx.transactionId(), totalScore, verdict, triggeredRuleNames);

        return new FraudVerdictEvent(
                UUID.randomUUID(),
                tx.transactionId(),
                verdict,
                totalScore,
                triggeredRuleNames,
                summary,
                java.time.Instant.now()
        );
    }
}
