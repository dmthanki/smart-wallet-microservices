package com.smartwallet.fraud.repository;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.enums.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.SequencedCollection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TransactionLogStore, focusing on the SequencedCollection API.
 */
@DisplayName("TransactionLogStore — SequencedCollection behaviour")
class TransactionLogStoreTest {

    private TransactionLogStore store;

    @BeforeEach
    void setUp() {
        store = new TransactionLogStore();
    }

    @Test
    @DisplayName("empty account returns empty SequencedCollection")
    void emptyAccount_returnsEmptyCollection() {
        SequencedCollection<TransactionDto> result =
                store.getRecentTransactions("unknown-account");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getLast() returns most recently recorded transaction")
    void getLast_returnsMostRecent() {
        var first  = deposit("acc-1", "1000");
        var second = deposit("acc-1", "2000");
        var third  = deposit("acc-1", "3000");

        store.record(first);
        store.record(second);
        store.record(third);

        // JAVA 21: getLast() — no list.get(list.size() - 1) arithmetic
        var log = store.getRecentTransactions("acc-1");
        assertThat(log.getLast().amount()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("getFirst() returns oldest recorded transaction")
    void getFirst_returnsOldest() {
        store.record(deposit("acc-2", "100"));
        store.record(deposit("acc-2", "200"));
        store.record(deposit("acc-2", "300"));

        // JAVA 21: getFirst() — no list.get(0)
        var log = store.getRecentTransactions("acc-2");
        assertThat(log.getFirst().amount()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("reversed() iterates in newest-first order without copying")
    void reversed_iteratesNewestFirst() {
        store.record(deposit("acc-3", "10"));
        store.record(deposit("acc-3", "20"));
        store.record(deposit("acc-3", "30"));

        var log = store.getRecentTransactions("acc-3");

        // JAVA 21: reversed() is a LIVE VIEW — no new collection created
        var amounts = log.reversed()
                .stream()
                .map(TransactionDto::amount)
                .toList();

        assertThat(amounts)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("30", "20", "10");
    }

    @Test
    @DisplayName("getLatestN returns N most recent in chronological order")
    void getLatestN_returnsChronological() {
        for (int i = 1; i <= 10; i++) {
            store.record(deposit("acc-4", String.valueOf(i * 100)));
        }

        var latest3 = store.getLatestN("acc-4", 3);

        assertThat(latest3).hasSize(3);
        var amounts = latest3.stream()
                .map(t -> t.amount().intValue())
                .toList();
        assertThat(amounts).containsExactly(800, 900, 1000);
    }

    @Test
    @DisplayName("getMostRecent returns Optional with latest tx")
    void getMostRecent_returnsLatest() {
        store.record(deposit("acc-5", "500"));
        store.record(deposit("acc-5", "999"));

        var mostRecent = store.getMostRecent("acc-5");

        assertThat(mostRecent).isPresent();
        assertThat(mostRecent.get().amount()).isEqualByComparingTo("999");
    }

    @Test
    @DisplayName("getMostRecent returns empty Optional for unknown account")
    void getMostRecent_unknown_returnsEmpty() {
        assertThat(store.getMostRecent("no-such-account")).isEmpty();
    }

    @Test
    @DisplayName("returned SequencedCollection is unmodifiable")
    void returnedCollection_isUnmodifiable() {
        store.record(deposit("acc-6", "100"));
        var log = store.getRecentTransactions("acc-6");

        // The returned view must not allow external mutation
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> log.addFirst(deposit("acc-6", "999")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture
    // ─────────────────────────────────────────────────────────────────────────

    private TransactionDto deposit(String accountId, String amount) {
        return TransactionDto.of(
                UUID.randomUUID().toString(),
                accountId,
                new BigDecimal(amount),
                "INR",
                new TransactionType.Deposit("bank-ref-" + UUID.randomUUID()),
                "user-test");
    }
}
