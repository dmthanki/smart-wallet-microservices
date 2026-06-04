package com.smartwallet.common.enums;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed hierarchy of transaction types.
 *
 * JAVA 21 vs TRADITIONAL:
 * Traditional: plain enum — no structural information, no compiler-enforced exhaustiveness
 * beyond switch statements, and no ability to carry type-specific payload in the type itself.
 *
 * Java 21: We use a SEALED INTERFACE hierarchy so that every transaction type is a distinct
 * record that can carry its own contextual data. Pattern matching for switch then gives us
 * exhaustive, compile-time-verified dispatch with zero casting.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = TransactionType.PeerToPeer.class, name = "PeerToPeer"),
    @JsonSubTypes.Type(value = TransactionType.Withdrawal.class, name = "Withdrawal"),
    @JsonSubTypes.Type(value = TransactionType.Deposit.class, name = "Deposit"),
    @JsonSubTypes.Type(value = TransactionType.MerchantPayment.class, name = "MerchantPayment")
})
public sealed interface TransactionType
        permits TransactionType.PeerToPeer,
                TransactionType.Withdrawal,
                TransactionType.Deposit,
                TransactionType.MerchantPayment {

    // ── Sealed subtypes ──────────────────────────────────────────────────────

    /**
     * P2P transfer between two wallet accounts within the platform.
     * @param recipientAccountId  destination wallet
     * @param note                optional memo visible to both parties
     */
    record PeerToPeer(String recipientAccountId, String note) implements TransactionType {}

    /**
     * Cash-out to an external bank account or ATM.
     * @param destinationBankCode  BIC / IFSC of receiving bank
     * @param instantTransfer      whether real-time rails were requested
     */
    record Withdrawal(String destinationBankCode, boolean instantTransfer) implements TransactionType {}

    /**
     * Funds arriving into the wallet from an external source.
     * @param sourceReference  reference string from the originating bank
     */
    record Deposit(String sourceReference) implements TransactionType {}

    /**
     * Payment to a registered merchant (online or POS).
     * @param merchantId    platform-registered merchant UUID
     * @param merchantName  human-readable name for display
     * @param mcc           ISO 18245 Merchant Category Code
     */
    record MerchantPayment(String merchantId, String merchantName, int mcc) implements TransactionType {}
}
