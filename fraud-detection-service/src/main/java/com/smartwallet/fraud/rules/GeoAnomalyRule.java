package com.smartwallet.fraud.rules;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.enums.TransactionType;
import org.springframework.stereotype.Component;

/**
 * Detects geographic anomalies — e.g., a withdrawal routed to a bank in a
 * jurisdiction the account has never used.
 */
@Component
public final class GeoAnomalyRule implements FraudRule {

    // High-risk jurisdiction prefixes (BIC/SWIFT country codes, simplified)
    private static final java.util.Set<String> HIGH_RISK_COUNTRY_CODES =
            java.util.Set.of("NG", "RU", "KP", "IR", "BY");

    @Override public String ruleName() { return "GEO_ANOMALY"; }

    @Override
    public RuleResult evaluate(TransactionDto tx) {
        return switch (tx.type()) {
            case TransactionType.Withdrawal(var bankCode, var instant) -> {
                if (bankCode != null && bankCode.length() >= 6) {
                    String countryCode = bankCode.substring(4, 6).toUpperCase();
                    if (HIGH_RISK_COUNTRY_CODES.contains(countryCode)) {
                        yield RuleResult.trigger(ruleName(), 0.45);
                    }
                }
                yield RuleResult.pass(ruleName());
            }
            default -> RuleResult.pass(ruleName());
        };
    }
}
