package com.smartwallet.fraud.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_rules",
        indexes = {
            @Index(name = "idx_fraud_rules_active", columnList = "priority ASC")
        })
public class FraudRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "rule_name", nullable = false, unique = true, length = 80)
    private String ruleName;

    @Column(name = "rule_type", nullable = false, length = 40)
    private String ruleType;

    @Column(name = "parameters", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String parameters = "{}";

    @Column(name = "score_weight", nullable = false, precision = 4, scale = 3)
    private BigDecimal scoreWeight = BigDecimal.ZERO;

    @Column(name = "priority", nullable = false)
    private short priority = 100;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getRuleName() { return ruleName; }
    public String getRuleType() { return ruleType; }
    public String getParameters() { return parameters; }
    public BigDecimal getScoreWeight() { return scoreWeight; }
    public short getPriority() { return priority; }
    public boolean isActive() { return active; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public void setParameters(String parameters) { this.parameters = parameters; }
    public void setScoreWeight(BigDecimal scoreWeight) { this.scoreWeight = scoreWeight; }
    public void setPriority(short priority) { this.priority = priority; }
    public void setActive(boolean active) { this.active = active; }
    public void setDescription(String description) { this.description = description; }
}
