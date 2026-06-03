package com.smartwallet.fraud.repository;

import com.smartwallet.common.dto.TransactionDto;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.SequencedCollection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, thread-safe store of recent transaction logs keyed by accountId.
 *
 * In production this would be backed by Redis (ZSET per accountId, scored by timestamp)
 * or a read-model table. The interface contract — SequencedCollection — remains the same.
 *
 * ════════════════════════════════════════════════════════════════════
 * WHY SequencedCollection IS THE RIGHT RETURN TYPE
 * ════════════════════════════════════════════════════════════════════
 *
 * TRADITIONAL Java 8/11: you'd return List<TransactionDto> or Deque<TransactionDto>.
 *   - If you return List, callers must call get(list.size()-1) for "latest" — fragile.
 *   - If you return Deque, callers get peekLast() but Deque isn't a subtype of List,
 *     so any code expecting List breaks.
 *   - You could return a Collections.unmodifiableList() but there's still no unified
 *     contract for "first/last access".
 *
 * JAVA 21: Return SequencedCollection<TransactionDto>.
 *   - Callers can call .getFirst(), .getLast(), .reversed() regardless of backing type.
 *   - The backing type (ArrayDeque here, could be Redis-backed LinkedList) is an
 *     implementation detail hidden behind the interface.
 *   - Collections.unmodifiableSequencedCollection() preserves the sequenced contract
 *     on the unmodifiable wrapper — something not possible with the old APIs.
 *
 * SEQUENCED COLLECTION NEW METHODS (Java 21):
 *   getFirst()  — O(1) for linked structures, no index arithmetic
 *   getLast()   — O(1) for linked structures
 *   addFirst()  — prepend
 *   addLast()   — append (same as add() for most implementations)
 *   removeFirst() / removeLast()
 *   reversed()  — live reverse-order VIEW, no copy made
 */
@Component
public class TransactionLogStore {

    // One ArrayDeque per account — insertion-ordered, supports O(1) head/tail access
    private final ConcurrentHashMap<String, ArrayDeque<TransactionDto>> store =
            new ConcurrentHashMap<>();

    private static final int    MAX_ENTRIES_PER_ACCOUNT = 200;
    private static final Duration RETENTION_WINDOW      = Duration.ofHours(2);

    /**
     * Records a transaction in the per-account log.
     * Maintains insertion order (newest appended to the tail).
     * Evicts entries older than the retention window.
     */
    public void record(TransactionDto tx) {
        store.compute(tx.sourceAccountId(), (accountId, deque) -> {
            if (deque == null) deque = new ArrayDeque<>(MAX_ENTRIES_PER_ACCOUNT);

            // JAVA 21: addLast() — idiomatic for appending to a SequencedCollection
            // TRADITIONAL: deque.offer() or deque.add() — semantically the same but
            // the intent (append to end) is less obvious.
            deque.addLast(tx);

            // Evict oldest entries
            evictStale(deque);
            return deque;
        });
    }

    /**
     * Returns an unmodifiable, insertion-ordered view of recent transactions
     * for the given account, newest at the TAIL (getLast() == most recent).
     *
     * RETURN TYPE: SequencedCollection<TransactionDto>
     *   Callers in VelocityRule use .reversed() to iterate newest-first.
     *   Callers in analytics can call .getLast() to peek at the most recent entry.
     *   No explicit cast or instanceof needed.
     */
    public SequencedCollection<TransactionDto> getRecentTransactions(String accountId) {
        ArrayDeque<TransactionDto> deque = store.get(accountId);
        if (deque == null || deque.isEmpty()) {
            // JAVA 21: SequencedCollection.of() for the empty case
            // Casting needed because Collections doesn't yet have sequenced helpers
            // for empty collections — use a wrapper for immutability
            return Collections.unmodifiableSequencedCollection(new ArrayDeque<>());
        }
        // Wrap in an unmodifiable view — preserves SequencedCollection contract
        return Collections.unmodifiableSequencedCollection(new ArrayDeque<>(deque));
    }

    /**
     * Retrieves the N most recent transactions using SequencedCollection semantics.
     *
     * TRADITIONAL: subList(list.size() - n, list.size()) — index arithmetic, fragile.
     * JAVA 21: iterate reversed() and collect up to n entries — intent is clear.
     */
    public SequencedCollection<TransactionDto> getLatestN(String accountId, int n) {
        SequencedCollection<TransactionDto> all = getRecentTransactions(accountId);
        ArrayDeque<TransactionDto> result = new ArrayDeque<>(n);

        // reversed() is a live VIEW — no intermediate collection allocated
        for (TransactionDto tx : all.reversed()) {
            if (result.size() >= n) break;
            result.addFirst(tx); // maintain chronological order in result
        }

        return Collections.unmodifiableSequencedCollection(result);
    }

    /**
     * Peek at the single most recent transaction without iterating.
     * TRADITIONAL: list.get(list.size() - 1) — index arithmetic, NullPointerException risk.
     * JAVA 21: getLast() — O(1), semantically explicit, throws NoSuchElementException
     *          (not ArrayIndexOutOfBoundsException) for empty collections.
     */
    public java.util.Optional<TransactionDto> getMostRecent(String accountId) {
        SequencedCollection<TransactionDto> log = getRecentTransactions(accountId);
        if (log.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(log.getLast());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void evictStale(ArrayDeque<TransactionDto> deque) {
        Instant cutoff = Instant.now().minus(RETENTION_WINDOW);

        // JAVA 21: removeIf on an ArrayDeque is efficient.
        // We also cap the size to avoid unbounded growth.
        deque.removeIf(tx -> tx.createdAt().isBefore(cutoff));

        while (deque.size() > MAX_ENTRIES_PER_ACCOUNT) {
            // JAVA 21: removeFirst() — idiomatic, clear intent (remove oldest entry)
            // TRADITIONAL: deque.pollFirst() — same result but returns null on empty
            //   (requiring a null-check) rather than throwing NoSuchElementException.
            deque.removeFirst();
        }
    }
}
