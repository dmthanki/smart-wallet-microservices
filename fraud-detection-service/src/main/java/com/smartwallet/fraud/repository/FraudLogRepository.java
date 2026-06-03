package com.smartwallet.fraud.repository;

import com.smartwallet.fraud.domain.FraudLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FraudLogRepository extends JpaRepository<FraudLogEntity, UUID> {
}
