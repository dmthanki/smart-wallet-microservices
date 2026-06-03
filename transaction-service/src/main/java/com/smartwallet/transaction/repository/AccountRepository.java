package com.smartwallet.transaction.repository;

import com.smartwallet.transaction.domain.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /**
     * Finds an account by ID with a pessimistic write lock (FOR UPDATE),
     * preventing concurrent modifications.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountEntity a WHERE a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Overload to support String-based ID lookups from the service layer.
     */
    default Optional<AccountEntity> findByIdForUpdate(String id) {
        try {
            return findByIdForUpdate(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Looks up a merchant account by merchantId.
     */
    Optional<AccountEntity> findByMerchantId(String merchantId);
}
