package com.smartwallet.fraud.rules;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.fraud.repository.TransactionLogStore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.SequencedCollection;

/**
 * Velocity rule: flag accounts that submit too many transactions
 * within a short rolling window.
 *
 * ════════════════════════════════════════════════════════════════════
 * JAVA 21 FEATURE: SequencedCollection (JEP 431)
 * ════════════════════════════════════════════════════════════════════
 *
 * Context: We need a time-ordered log of recent transactions per account
 * to answer: "How many transactions has this account made in the last 60s?"
 *
 * TRADITIONAL Java 8/11 approach — multiple scattered APIs, no unified contract:
 *
 *   List<TransactionDto> log = ...;
 *   // Want the most recent element?
 *   TransactionDto last = log.get(log.size() - 1);      // List: index arithmetic
 *   TransactionDto last = deque.peekLast();              // Deque: different API
 *   TransactionDto last = treeSet.last();                // SortedSet: yet another API
 *
 *   // Want to add to the front?
 *   list.add(0, tx);                                     // O(n) for ArrayList, no explicit API
 *   deque.addFirst(tx);                                  // Deque only
 *
 *   // The problem: you had to know which concrete collection you were using.
 *   // Code that accepted a List<T> parameter couldn't call .peekLast() without casting.
 *
 * JAVA 21 — SequencedCollection interface:
 *
 *   SequencedCollection<TransactionDto> log = new ArrayDeque<>();
 *
 *   log.addFirst(tx);           // unified API regardless of backing store
 *   log.addLast(tx);
 *   TransactionDto first = log.getFirst();   // clean, no index arithmetic
 *   TransactionDto last  = log.getLast();    // guaranteed O(1) for LinkedList/Deque
 *   SequencedCollection<TransactionDto> reversed = log.reversed(); // live view, no copy
 *
 *   // You can accept SequencedCollection<T> in method signatures — any ordered
 *   // collection (List, Deque, LinkedHashSet) satisfies the contract. This is the
 *   // key improvement: you now have a COMMON TYPE for "an ordered collection
 *   // with defined first and last elements."
 *
 * SequencedCollection hierarchy:
 *   Iterable
 *   └── Collection
 *       └── SequencedCollection      ← NEW in Java 21
 *           ├── List
 *           ├── Deque
 *           └── SequencedSet
 *               └── SortedSet
 */
@Component
public final class VelocityRule implements FraudRule {

    private static final String RULE_NAME          = "VELOCITY_BREACH";
    private static final int    MAX_TXN_PER_MINUTE = 5;
    private static final int    MAX_TXN_PER_HOUR   = 20;
    private static final double MINUTE_SCORE       = 0.55;
    private static final double HOUR_SCORE         = 0.30;

    private final TransactionLogStore logStore;

    public VelocityRule(TransactionLogStore logStore) {
        this.logStore = logStore;
    }

    @Override
    public String ruleName() {
        return RULE_NAME;
    }

    @Override
    public RuleResult evaluate(TransactionDto tx) {

        // ── Retrieve time-ordered log as a SequencedCollection ────────────────
        //
        // TRADITIONAL: logStore would return List<TransactionDto> and we'd call
        //   logs.get(logs.size() - 1) to get the latest, or sort manually.
        //
        // JAVA 21: logStore returns SequencedCollection<TransactionDto> — the
        // caller doesn't need to know whether it's an ArrayList, ArrayDeque, or
        // LinkedHashSet. The contract "ordered with accessible first/last" is
        // encoded in the type.
        //
        SequencedCollection<TransactionDto> recentLogs =
                logStore.getRecentTransactions(tx.sourceAccountId());

        if (recentLogs.isEmpty()) {
            return RuleResult.pass(RULE_NAME);
        }

        Instant now    = Instant.now();
        Instant oneMin = now.minus(Duration.ofMinutes(1));
        Instant oneHr  = now.minus(Duration.ofHours(1));

        // ── Count transactions in the rolling windows ─────────────────────────
        //
        // TRADITIONAL: stream().filter(...).count() — fine, but iterates the whole list.
        // For a time-ordered SequencedCollection we can short-circuit from the tail.
        //
        // Here we use reversed() to iterate newest-first and break early once we
        // pass outside the window — no full scan needed.
        //
        // JAVA 21: log.reversed() returns a LIVE VIEW (no copy) iterating in reverse
        // insertion order. Combined with a simple loop, this is more performant than
        // a full stream scan for large logs.
        //
        int countLastMinute = 0;
        int countLastHour   = 0;

        for (TransactionDto past : recentLogs.reversed()) {
            // Once we go past the 1-hour mark, all earlier entries are also outside
            // our window — break entirely.
            if (past.createdAt().isBefore(oneHr)) break;

            countLastHour++;

            if (past.createdAt().isAfter(oneMin)) {
                countLastMinute++;
            }
        }

        // ── Evaluate thresholds ───────────────────────────────────────────────
        if (countLastMinute >= MAX_TXN_PER_MINUTE) {
            return RuleResult.trigger(RULE_NAME + "_PER_MINUTE", MINUTE_SCORE);
        }

        if (countLastHour >= MAX_TXN_PER_HOUR) {
            return RuleResult.trigger(RULE_NAME + "_PER_HOUR", HOUR_SCORE);
        }

        return RuleResult.pass(RULE_NAME);
    }
}
