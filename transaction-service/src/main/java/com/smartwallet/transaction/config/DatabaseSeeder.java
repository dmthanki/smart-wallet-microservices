package com.smartwallet.transaction.config;

import com.smartwallet.transaction.domain.AccountEntity;
import com.smartwallet.transaction.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Seeds default accounts for local development if the database is empty.
 */
@Component
public class DatabaseSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);
    private final AccountRepository accountRepo;

    public DatabaseSeeder(AccountRepository accountRepo) {
        this.accountRepo = accountRepo;
    }

    @Override
    public void run(String... args) {
        if (accountRepo.count() == 0) {
            log.info("Database is empty. Seeding default accounts...");

            AccountEntity acc1 = new AccountEntity(
                    UUID.fromString("a1111111-1111-1111-1111-111111111111"),
                    "user-123",
                    "ACC-998877",
                    "PERSONAL",
                    new BigDecimal("100000.00"),
                    BigDecimal.ZERO,
                    "INR",
                    "ACTIVE",
                    null
            );

            AccountEntity acc2 = new AccountEntity(
                    UUID.fromString("b2222222-2222-2222-2222-222222222222"),
                    "user-456",
                    "ACC-112233",
                    "PERSONAL",
                    new BigDecimal("50000.00"),
                    BigDecimal.ZERO,
                    "INR",
                    "ACTIVE",
                    null
            );

            AccountEntity acc3 = new AccountEntity(
                    UUID.fromString("c3333333-3333-3333-3333-333333333333"),
                    "merchant-abc",
                    "ACC-554433",
                    "MERCHANT",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "INR",
                    "ACTIVE",
                    "merchant-abc"
            );

            accountRepo.saveAll(List.of(acc1, acc2, acc3));
            log.info("Default accounts seeded successfully!");
        } else {
            log.info("Database already contains accounts. Skipping seeding.");
        }
    }
}
