package com.smartwallet.transaction.service;

import com.smartwallet.common.dto.TransactionDto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotency service backed by an in-memory store with TTL eviction.
 *
 * In production: back this with Redis (SETNX + EXPIRE) or a PostgreSQL
 * idempotency_keys table with a partial unique index on (key, status).
 *
 * JAVA 21 features:
 *   • IdempotencyEntry is a RECORD — a natural fit for a cache value
 *     that is pure data: the stored dto + the time it was registered.
 *   • computeIfAbsent and pattern matching used for clean conditional logic.
 */
@Service
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);

    // In-memory store: idempotencyKey → cache entry
    private final ConcurrentHashMap<String, IdempotencyEntry> cache = new ConcurrentHashMap<>();

    /**
     * JAVA 21 Record as a value type in a cache.
     *
     * TRADITIONAL: A mutable inner class with getter methods and hand-written equals.
     * JAVA 21: A record — components are final by nature, equals/hashCode are free.
     */
    private record IdempotencyEntry(TransactionDto dto, Instant registeredAt) {
        boolean isExpired() {
            return Instant.now().isAfter(registeredAt.plus(TTL));
        }
    }

    /**
     * Look up a previously registered transaction by idempotency key.
     */
    public Optional<TransactionDto> find(String idempotencyKey) {
        IdempotencyEntry entry = cache.get(idempotencyKey);
        if (entry == null || entry.isExpired()) {
            cache.remove(idempotencyKey); // lazy eviction
            return Optional.empty();
        }
        return Optional.of(entry.dto());
    }

    /**
     * Register a transaction after successful persistence.
     */
    public void register(TransactionDto dto) {
        cache.put(dto.idempotencyKey(),
                new IdempotencyEntry(dto, Instant.now()));
    }
}
