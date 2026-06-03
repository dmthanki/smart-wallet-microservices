package com.smartwallet.fraud.rules;

import com.smartwallet.common.dto.TransactionDto;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Transactions initiated between 01:00–05:00 local time carry additional risk.
 */
@Component
public final class OffHoursRule implements FraudRule {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Override public String ruleName() { return "OFF_HOURS"; }

    @Override
    public RuleResult evaluate(TransactionDto tx) {
        int hour = ZonedDateTime.ofInstant(tx.createdAt(), IST).getHour();

        double score = switch (hour) {
            case 1, 2, 3, 4, 5 -> 0.20;
            default             -> 0.0;
        };

        return score > 0
                ? RuleResult.trigger(ruleName(), score)
                : RuleResult.pass(ruleName());
    }
}
