package com.smartwallet.fraud.engine;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.enums.TransactionType;
import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.common.events.FraudVerdictEvent.Verdict;
import com.smartwallet.fraud.repository.TransactionLogStore;
import com.smartwallet.fraud.rules.AmountThresholdRule;
import com.smartwallet.fraud.rules.FraudRule;
import com.smartwallet.fraud.rules.GeoAnomalyRule;
import com.smartwallet.fraud.rules.OffHoursRule;
import com.smartwallet.fraud.rules.VelocityRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for FraudRuleEngine.
 */
@DisplayName("FraudRuleEngine — Pattern Matching Evaluation")
class FraudRuleEngineTest {

    private FraudRuleEngine engine;
    private TransactionLogStore logStore;

    @BeforeEach
    void setUp() {
        logStore = new TransactionLogStore();
        List<FraudRule> rules = List.of(
                new VelocityRule(logStore),
                new AmountThresholdRule(),
                new GeoAnomalyRule(),
                new OffHoursRule()
        );
        engine = new FraudRuleEngine(rules);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransactionDto Record — construction & validation tests
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TransactionDto record — construction")
    class TransactionDtoConstructionTests {

        @Test
        @DisplayName("static factory creates valid DTO with INITIATED status")
        void staticFactory_createsValidDto() {
            var dto = TransactionDto.of(
                    "idem-key-001",
                    "acc-src-001",
                    new BigDecimal("1000.00"),
                    "INR",
                    new TransactionType.PeerToPeer("acc-dst-001", "lunch split"),
                    "user-001");

            assertThat(dto.idempotencyKey()).isEqualTo("idem-key-001");
            assertThat(dto.status()).isEqualTo(TransactionDto.TransactionStatus.INITIATED);
            assertThat(dto.amount()).isEqualByComparingTo("1000.00");
            assertThat(dto.transactionId()).isNotNull();
            assertThat(dto.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("compact canonical constructor rejects non-positive amount")
        void compactConstructor_rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> TransactionDto.of(
                    "idem-key-002", "acc-src-001",
                    BigDecimal.ZERO, "INR",
                    new TransactionType.Deposit("ref-001"), "user-001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Transaction amount must be positive");
        }

        @Test
        @DisplayName("compact canonical constructor rejects blank idempotencyKey")
        void compactConstructor_rejectsBlankIdempotencyKey() {
            assertThatThrownBy(() -> TransactionDto.of(
                    "   ", "acc-src-001",
                    new BigDecimal("500"), "INR",
                    new TransactionType.Deposit("ref-001"), "user-001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("idempotencyKey must not be blank");
        }

        @Test
        @DisplayName("withStatus wither returns new record, original unchanged")
        void wither_returnsNewRecord_originalUnchanged() {
            var original = TransactionDto.of(
                    "idem-key-003", "acc-src-001",
                    new BigDecimal("500"), "INR",
                    new TransactionType.Deposit("ref-001"), "user-001");

            var updated = original.withStatus(TransactionDto.TransactionStatus.PROCESSED);

            assertThat(updated).isNotSameAs(original);
            assertThat(updated.status()).isEqualTo(TransactionDto.TransactionStatus.PROCESSED);
            assertThat(original.status()).isEqualTo(TransactionDto.TransactionStatus.INITIATED);

            assertThat(updated.transactionId()).isEqualTo(original.transactionId());
            assertThat(updated.amount()).isEqualByComparingTo(original.amount());
        }

        @Test
        @DisplayName("builder pattern produces equivalent DTO to static factory")
        void builder_producesEquivalentDto() {
            var type = new TransactionType.Withdrawal("HDFC0001234", false);

            var fromFactory = TransactionDto.of(
                    "idem-key-004", "acc-src-001",
                    new BigDecimal("25000"), "INR", type, "user-001");

            var fromBuilder = TransactionDto.builder()
                    .transactionId(fromFactory.transactionId())
                    .idempotencyKey("idem-key-004")
                    .sourceAccountId("acc-src-001")
                    .amount(new BigDecimal("25000"))
                    .currency("INR")
                    .type(type)
                    .initiatedByUserId("user-001")
                    .createdAt(fromFactory.createdAt())
                    .build();

            assertThat(fromBuilder).isEqualTo(fromFactory);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern Matching switch — PeerToPeer
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PeerToPeer transactions")
    class PeerToPeerTests {

        @Test
        @DisplayName("normal P2P below threshold → PASS")
        void normalP2P_belowThreshold_pass() {
            var tx = p2p("acc-src", "acc-dst", "5000.00");
            FraudVerdictEvent verdict = engine.evaluate(tx);

            assertThat(verdict.verdict()).isEqualTo(Verdict.PASS);
            assertThat(verdict.triggeredRules()).doesNotContain("HIGH_VALUE_P2P");
        }

        @Test
        @DisplayName("P2P above ₹50,000 threshold → FLAG or BLOCK depending on total score")
        void highValueP2P_aboveThreshold_triggersRule() {
            var tx = p2p("acc-src", "acc-dst", "75000.00");
            FraudVerdictEvent verdict = engine.evaluate(tx);

            assertThat(verdict.triggeredRules()).contains("HIGH_VALUE_P2P");
            assertThat(verdict.riskScore()).isGreaterThan(0.30);
        }

        @Test
        @DisplayName("self-transfer attempt → BLOCK (score ≥ 0.80)")
        void selfTransfer_triggers_block() {
            var tx = p2p("acc-self", "acc-self", "100.00");
            FraudVerdictEvent verdict = engine.evaluate(tx);

            assertThat(verdict.triggeredRules()).contains("SELF_TRANSFER_ATTEMPT");
            assertThat(verdict.verdict()).isEqualTo(Verdict.BLOCK);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern Matching switch — Withdrawal
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Withdrawal transactions")
    class WithdrawalTests {

        @Test
        @DisplayName("standard withdrawal below threshold → PASS")
        void normalWithdrawal_pass() {
            var tx = withdrawal("acc-src", "10000.00", "HDFCINBB", false);
            assertThat(engine.evaluate(tx).verdict()).isEqualTo(Verdict.PASS);
        }

        @Test
        @DisplayName("instant withdrawal above ₹25,000 → triggers HIGH_VALUE_INSTANT_WITHDRAWAL")
        void instantWithdrawal_aboveLimit_triggers() {
            var tx = withdrawal("acc-src", "30000.00", "HDFCINBB", true);
            var verdict = engine.evaluate(tx);

            assertThat(verdict.triggeredRules()).contains("HIGH_VALUE_INSTANT_WITHDRAWAL");
            assertThat(verdict.riskScore()).isGreaterThanOrEqualTo(0.50);
        }

        @Test
        @DisplayName("standard withdrawal above ₹1,00,000 → triggers HIGH_VALUE_WITHDRAWAL")
        void standardWithdrawal_aboveLimit_triggers() {
            var tx = withdrawal("acc-src", "150000.00", "HDFCINBB", false);
            var verdict = engine.evaluate(tx);

            assertThat(verdict.triggeredRules()).contains("HIGH_VALUE_WITHDRAWAL");
        }

        @Test
        @DisplayName("withdrawal to high-risk jurisdiction → GEO_ANOMALY triggered")
        void withdrawalToHighRiskCountry_triggersGeoAnomaly() {
            var tx = withdrawal("acc-src", "5000.00", "ZENBNGLAX", false);
            var verdict = engine.evaluate(tx);

            assertThat(verdict.triggeredRules()).contains("GEO_ANOMALY");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern Matching switch — MerchantPayment
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MerchantPayment transactions")
    class MerchantPaymentTests {

        @Test
        @DisplayName("safe merchant (grocery, MCC 5411) → PASS")
        void safemerchant_pass() {
            var tx = merchantPayment("acc-src", "2500.00", "merch-001", "Big Bazaar", 5411);
            assertThat(engine.evaluate(tx).verdict()).isEqualTo(Verdict.PASS);
        }

        @Test
        @DisplayName("gambling merchant (MCC 7995) above limit → HIGH_RISK_MCC_PAYMENT")
        void gamblingMerchant_aboveLimit_triggersHighRiskMcc() {
            var tx = merchantPayment("acc-src", "50000.00", "merch-casino", "Lucky Casino", 7995);
            var verdict = engine.evaluate(tx);

            assertThat(verdict.triggeredRules()).contains("HIGH_RISK_MCC_PAYMENT");
            assertThat(verdict.triggeredRules()).contains("MCC_7995");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FraudVerdictEvent record — validation tests
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FraudVerdictEvent record invariants")
    class FraudVerdictEventTests {

        @Test
        @DisplayName("risk score outside [0, 1] throws IllegalArgumentException")
        void invalidRiskScore_throws() {
            assertThatThrownBy(() -> new FraudVerdictEvent(
                    java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID(),
                    Verdict.FLAG,
                    1.5,
                    List.of(),
                    "summary",
                    java.time.Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("riskScore must be in [0.0, 1.0]");
        }

        @Test
        @DisplayName("triggeredRules list is defensively copied — caller mutation has no effect")
        void triggeredRules_isDefensivelyCopied() {
            var mutableList = new java.util.ArrayList<String>();
            mutableList.add("RULE_A");

            var event = new FraudVerdictEvent(
                    java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    Verdict.PASS, 0.1, mutableList, "summary", java.time.Instant.now());

            mutableList.add("INJECTED_RULE");

            assertThat(event.triggeredRules()).containsExactly("RULE_A");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test fixture helpers
    // ─────────────────────────────────────────────────────────────────────────

    private TransactionDto p2p(String src, String dst, String amount) {
        return TransactionDto.of(
                java.util.UUID.randomUUID().toString(),
                src, new BigDecimal(amount), "INR",
                new TransactionType.PeerToPeer(dst, "test"),
                "user-test");
    }

    private TransactionDto withdrawal(String src, String amount, String bankCode, boolean instant) {
        return TransactionDto.of(
                java.util.UUID.randomUUID().toString(),
                src, new BigDecimal(amount), "INR",
                new TransactionType.Withdrawal(bankCode, instant),
                "user-test");
    }

    private TransactionDto merchantPayment(
            String src, String amount, String merchantId, String name, int mcc) {
        return TransactionDto.of(
                java.util.UUID.randomUUID().toString(),
                src, new BigDecimal(amount), "INR",
                new TransactionType.MerchantPayment(merchantId, name, mcc),
                "user-test");
    }
}
