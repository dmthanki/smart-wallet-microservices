package com.smartwallet.transaction.api;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.enums.TransactionType;
import com.smartwallet.transaction.service.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * REST controller for the Transaction Service.
 *
 * JAVA 21 — Request records:
 * ──────────────────────────
 * TRADITIONAL: A @RequestBody POJO class with getters, setters, @NotNull annotations,
 * and a separate @Valid binding. Each endpoint has its own mutable request class.
 *
 * JAVA 21: Request types are RECORDS. Jackson 2.12+ deserialises records natively
 * via the canonical constructor (no @JsonCreator needed with Jackson's
 * RecordNamingStrategyPatchModule). Bean Validation (@Valid) works on record
 * components. The compact canonical constructor can enforce invariants.
 *
 * NOTE: Records cannot be marked @Valid directly on Spring's default setup without
 * enabling constructor-based validation — use @Validated at class level + add
 * spring-boot-starter-validation dependency.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    // ── Request Records ───────────────────────────────────────────────────────

    /**
     * P2P transfer request.
     * JAVA 21: record components are the contract — no setter surface, no mutation risk.
     */
    public record P2PTransferRequest(
            String idempotencyKey,
            String sourceAccountId,
            String recipientAccountId,
            BigDecimal amount,
            String currency,
            String note,
            String initiatedByUserId) {}

    public record WithdrawalRequest(
            String idempotencyKey,
            String sourceAccountId,
            BigDecimal amount,
            String currency,
            String destinationBankCode,
            boolean instantTransfer,
            String initiatedByUserId) {}

    public record MerchantPaymentRequest(
            String idempotencyKey,
            String sourceAccountId,
            BigDecimal amount,
            String currency,
            String merchantId,
            String merchantName,
            int mcc,
            String initiatedByUserId) {}

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @PostMapping("/p2p")
    public ResponseEntity<TransactionDto> p2pTransfer(
            @RequestBody P2PTransferRequest req) {

        // JAVA 21: static factory on TransactionDto — cleaner than builder for
        // fully-specified creation. The TransactionType.PeerToPeer record is
        // constructed inline — no intermediate variable needed.
        TransactionDto dto = TransactionDto.of(
                req.idempotencyKey(),
                req.sourceAccountId(),
                req.amount(),
                req.currency(),
                new TransactionType.PeerToPeer(req.recipientAccountId(), req.note()),
                req.initiatedByUserId());

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(service.initiate(dto));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionDto> withdraw(
            @RequestBody WithdrawalRequest req) {

        TransactionDto dto = TransactionDto.of(
                req.idempotencyKey(),
                req.sourceAccountId(),
                req.amount(),
                req.currency(),
                new TransactionType.Withdrawal(req.destinationBankCode(), req.instantTransfer()),
                req.initiatedByUserId());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.initiate(dto));
    }

    @PostMapping("/merchant-pay")
    public ResponseEntity<TransactionDto> merchantPay(
            @RequestBody MerchantPaymentRequest req) {

        TransactionDto dto = TransactionDto.of(
                req.idempotencyKey(),
                req.sourceAccountId(),
                req.amount(),
                req.currency(),
                new TransactionType.MerchantPayment(req.merchantId(), req.merchantName(), req.mcc()),
                req.initiatedByUserId());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.initiate(dto));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDto> getTransaction(@PathVariable String transactionId) {
        // TODO: wire to query service
        return ResponseEntity.noContent().build();
    }
}
