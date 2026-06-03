package com.smartwallet.transaction.service;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.dto.TransactionDto.TransactionStatus;
import com.smartwallet.common.enums.TransactionType;
import com.smartwallet.common.events.FraudVerdictEvent;
import com.smartwallet.transaction.domain.AccountEntity;
import com.smartwallet.transaction.domain.TransactionEntity;
import com.smartwallet.transaction.kafka.TransactionEventPublisher;
import com.smartwallet.transaction.repository.AccountRepository;
import com.smartwallet.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Core transaction orchestration service.
 *
 * Responsibilities:
 *   1. Validate and persist incoming transaction requests (with idempotency).
 *   2. Publish domain events to Kafka via the transactional outbox.
 *   3. React to FraudVerdictEvents and update transaction status accordingly.
 *   4. Apply balance mutations only after fraud clearance.
 *
 * ════════════════════════════════════════════════════════════════════
 * JAVA 21 features used here:
 * ════════════════════════════════════════════════════════════════════
 *   • Pattern matching for switch — applyVerdict() dispatches on Verdict enum
 *     and TransactionType sealed hierarchy without casts.
 *   • Record deconstruction — extracting TransactionDto components cleanly.
 *   • Text blocks — SQL and log messages.
 *   • Switch expressions — replacing if-else chains with concise, typed results.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepo;
    private final AccountRepository     accountRepo;
    private final TransactionEventPublisher publisher;
    private final IdempotencyService    idempotencyService;

    public TransactionService(
            TransactionRepository transactionRepo,
            AccountRepository accountRepo,
            TransactionEventPublisher publisher,
            IdempotencyService idempotencyService) {
        this.transactionRepo    = transactionRepo;
        this.accountRepo        = accountRepo;
        this.publisher          = publisher;
        this.idempotencyService = idempotencyService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INITIATE TRANSACTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for all transaction types. Persists, publishes for fraud
     * analysis, and returns the pending DTO.
     *
     * The balance is NOT modified here — it is held in escrow until the
     * FraudVerdictEvent arrives via Kafka.
     */
    @Transactional
    public TransactionDto initiate(TransactionDto request) {

        // ── 1. Idempotency guard ──────────────────────────────────────────────
        Optional<TransactionDto> existing =
                idempotencyService.find(request.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay for key [{}] — returning cached result",
                    request.idempotencyKey());
            return existing.get();
        }

        // ── 2. Balance pre-check ──────────────────────────────────────────────
        AccountEntity source = accountRepo.findByIdForUpdate(request.sourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.sourceAccountId()));

        validateSufficientBalance(source, request);

        // ── 3. Reserve (escrow) the amount ────────────────────────────────────
        source.reserveBalance(request.amount());
        accountRepo.save(source);

        // ── 4. Persist transaction in PENDING_FRAUD_CHECK state ───────────────
        TransactionDto pending = request.withStatus(TransactionStatus.PENDING_FRAUD_CHECK);
        transactionRepo.save(TransactionEntity.from(pending));

        // ── 5. Publish to Kafka via transactional outbox ──────────────────────
        // The outbox pattern guarantees at-least-once delivery even if Kafka is down.
        publisher.publish(pending);

        // ── 6. Register in idempotency store ─────────────────────────────────
        idempotencyService.register(pending);

        log.info("Transaction [{}] initiated, pending fraud check", pending.transactionId());
        return pending;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APPLY FRAUD VERDICT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by the Kafka consumer when a FraudVerdictEvent arrives.
     *
     * JAVA 21 — Pattern matching for switch on a sealed type + record components:
     *
     * TRADITIONAL Java 8/11:
     *   if (event.verdict() == Verdict.PASS) {
     *       applyPass(tx);
     *   } else if (event.verdict() == Verdict.FLAG) {
     *       applyFlag(tx, event);
     *   } else if (event.verdict() == Verdict.BLOCK) {
     *       applyBlock(tx, event);
     *   }
     *   // Returns void — caller has no way to know the new status without querying.
     *
     * JAVA 21: switch expression that RETURNS the final TransactionDto —
     *   result-typed dispatch, no mutation through side-channels.
     */
    @Transactional
    public TransactionDto applyVerdict(FraudVerdictEvent event) {

        TransactionEntity entity = transactionRepo
                .findById(event.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(event.transactionId()));

        TransactionDto tx = entity.toDto();

        // ── Pattern match on verdict enum — exhaustive via sealed Verdict enum ─
        TransactionDto updated = switch (event.verdict()) {

            case PASS -> {
                log.info("Fraud PASS for [{}], proceeding to settlement", tx.transactionId());
                yield settleTransaction(tx);
            }

            case FLAG -> {
                // Flagged: transaction goes through but is marked for manual review.
                // We still settle (don't block the user) but create an audit record.
                log.warn("Fraud FLAG for [{}], rules={}", tx.transactionId(),
                        event.triggeredRules());
                TransactionDto flagged = tx.withStatus(TransactionStatus.FRAUD_FLAGGED);
                yield settleTransaction(flagged);
            }

            case BLOCK -> {
                log.error("Fraud BLOCK for [{}], score={}, rules={}",
                        tx.transactionId(), event.riskScore(), event.triggeredRules());
                yield reverseEscrow(tx);
            }
        };

        transactionRepo.save(TransactionEntity.from(updated));
        return updated;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SETTLEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies the balance mutation for a cleared transaction.
     *
     * JAVA 21 — Record Pattern in switch to destructure TransactionType
     * and route to type-specific settlement logic inline.
     */
    private TransactionDto settleTransaction(TransactionDto tx) {

        AccountEntity source = accountRepo
                .findByIdForUpdate(tx.sourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException(tx.sourceAccountId()));

        // Commit the reserved amount (debit from escrow to actual debit)
        source.commitReservation(tx.amount());
        accountRepo.save(source);

        // ── Type-specific destination credit ─────────────────────────────────
        //
        // JAVA 21: Record pattern extracts components for each type inline.
        // No casts, no intermediate variables, compiler-enforced exhaustiveness.
        //
        // TRADITIONAL: instanceof + cast chain — see FraudRuleEngine for comparison.
        switch (tx.type()) {

            case TransactionType.PeerToPeer(var recipientId, var note) -> {
                AccountEntity recipient = accountRepo
                        .findByIdForUpdate(recipientId)
                        .orElseThrow(() -> new AccountNotFoundException(recipientId));
                recipient.credit(tx.amount());
                accountRepo.save(recipient);
                log.info("P2P settled: {} → {} amount={}",
                        tx.sourceAccountId(), recipientId, tx.amount());
            }

            case TransactionType.Withdrawal(var bankCode, var instant) -> {
                // External bank transfer — initiate via payment rail adapter (out of scope here)
                log.info("Withdrawal settled to bank={} instant={} amount={}",
                        bankCode, instant, tx.amount());
            }

            case TransactionType.MerchantPayment(var merchantId, var name, var mcc) -> {
                // Credit merchant's wallet account
                AccountEntity merchant = accountRepo
                        .findByMerchantId(merchantId)
                        .orElseThrow(() -> new AccountNotFoundException("merchant:" + merchantId));
                merchant.credit(tx.amount());
                accountRepo.save(merchant);
                log.info("Merchant payment settled to {} (mcc={})", name, mcc);
            }

            case TransactionType.Deposit(var sourceRef) -> {
                // Source funds arrive from external — credit already handled by banking adapter
                source.credit(tx.amount());
                accountRepo.save(source);
                log.info("Deposit settled from ref={}", sourceRef);
            }
        }

        return tx.withStatus(TransactionStatus.PROCESSED)
                 .withProcessedAt(Instant.now());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REVERSAL
    // ─────────────────────────────────────────────────────────────────────────

    private TransactionDto reverseEscrow(TransactionDto tx) {
        AccountEntity source = accountRepo
                .findByIdForUpdate(tx.sourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException(tx.sourceAccountId()));

        source.releaseReservation(tx.amount());
        accountRepo.save(source);

        log.warn("Escrow released for BLOCKED transaction [{}]", tx.transactionId());
        return tx.withStatus(TransactionStatus.FRAUD_BLOCKED)
                 .withProcessedAt(Instant.now());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VALIDATION
    // ─────────────────────────────────────────────────────────────────────────

    private void validateSufficientBalance(AccountEntity account, TransactionDto tx) {

        // JAVA 21 switch expression — maps TransactionType to the required available balance.
        // Deposits don't require a balance check; all other types do.
        boolean requiresBalance = switch (tx.type()) {
            case TransactionType.Deposit ignored -> false;
            default                              -> true;
        };

        if (requiresBalance && account.getAvailableBalance().compareTo(tx.amount()) < 0) {
            throw new InsufficientBalanceException(
                    account.getId().toString(), account.getAvailableBalance(), tx.amount());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INNER EXCEPTION TYPES — scoped to the service layer
    // ─────────────────────────────────────────────────────────────────────────

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String accountId) {
            super("Account not found: " + accountId);
        }
    }

    public static class TransactionNotFoundException extends RuntimeException {
        public TransactionNotFoundException(UUID txId) {
            super("Transaction not found: " + txId);
        }
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String id, BigDecimal available, BigDecimal required) {
            super("Insufficient balance on account %s: available=%s, required=%s"
                    .formatted(id, available, required));
        }
    }
}
