package com.smartwallet.transaction.api;

import com.smartwallet.common.dto.TransactionDto;
import com.smartwallet.common.enums.TransactionType;
import com.smartwallet.transaction.service.TransactionService;
import com.smartwallet.transaction.domain.AccountEntity;
import com.smartwallet.transaction.domain.TransactionEntity;
import com.smartwallet.transaction.repository.AccountRepository;
import com.smartwallet.transaction.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService service;
    private final AccountRepository accountRepo;
    private final TransactionRepository transactionRepo;

    public TransactionController(
            TransactionService service,
            AccountRepository accountRepo,
            TransactionRepository transactionRepo) {
        this.service = service;
        this.accountRepo = accountRepo;
        this.transactionRepo = transactionRepo;
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

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountEntity>> getAccounts() {
        return ResponseEntity.ok(accountRepo.findAll());
    }

    @GetMapping
    public ResponseEntity<List<TransactionDto>> getAllTransactions() {
        List<TransactionDto> list = transactionRepo.findAll()
                .stream()
                .map(TransactionEntity::toDto)
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
        return ResponseEntity.ok(list);
    }
}
