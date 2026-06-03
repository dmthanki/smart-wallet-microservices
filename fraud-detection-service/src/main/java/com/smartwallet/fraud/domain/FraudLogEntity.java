package com.smartwallet.fraud.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_logs",
        indexes = {
            @Index(name = "idx_fraud_logs_txn", columnList = "transaction_id"),
            @Index(name = "idx_fraud_logs_verdict_time", columnList = "verdict, evaluated_at DESC"),
            @Index(name = "idx_fraud_logs_unreviewed", columnList = "evaluated_at ASC"),
            @Index(name = "idx_fraud_logs_rules_gin", columnList = "triggered_rules"),
            @Index(name = "idx_fraud_logs_account", columnList = "source_account_id, evaluated_at DESC")
        })
public class FraudLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "source_account_id", nullable = false, length = 100)
    private String sourceAccountId;

    @Column(name = "risk_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal riskScore = BigDecimal.ZERO;

    @Column(name = "verdict", nullable = false, length = 10)
    private String verdict;

    @Column(name = "triggered_rules", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String triggeredRules = "[]";

    @Column(name = "analysis_summary", columnDefinition = "text")
    private String analysisSummary;

    @Column(name = "analyst_override", length = 20)
    private String analystOverride;

    @Column(name = "analyst_notes", columnDefinition = "text")
    private String analystNotes;

    @Column(name = "evaluated_at", updatable = false, nullable = false)
    private Instant evaluatedAt;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    @PrePersist
    void onCreate() {
        evaluatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public String getSourceAccountId() { return sourceAccountId; }
    public BigDecimal getRiskScore() { return riskScore; }
    public String getVerdict() { return verdict; }
    public String getTriggeredRules() { return triggeredRules; }
    public String getAnalysisSummary() { return analysisSummary; }
    public String getAnalystOverride() { return analystOverride; }
    public String getAnalystNotes() { return analystNotes; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public Instant getOverriddenAt() { return overriddenAt; }

    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public void setSourceAccountId(String sourceAccountId) { this.sourceAccountId = sourceAccountId; }
    public void setRiskScore(BigDecimal riskScore) { this.riskScore = riskScore; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public void setTriggeredRules(String triggeredRules) { this.triggeredRules = triggeredRules; }
    public void setAnalysisSummary(String analysisSummary) { this.analysisSummary = analysisSummary; }
    public void setAnalystOverride(String analystOverride) { this.analystOverride = analystOverride; }
    public void setAnalystNotes(String analystNotes) { this.analystNotes = analystNotes; }
}
