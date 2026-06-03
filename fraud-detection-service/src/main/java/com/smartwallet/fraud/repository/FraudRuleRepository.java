package com.smartwallet.fraud.repository;

import com.smartwallet.fraud.domain.FraudRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FraudRuleRepository extends JpaRepository<FraudRuleEntity, UUID> {
}
