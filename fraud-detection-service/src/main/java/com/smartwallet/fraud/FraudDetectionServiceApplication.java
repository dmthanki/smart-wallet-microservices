package com.smartwallet.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the Fraud Detection Service.
 * Runs on port 8082.
 */
@SpringBootApplication
public class FraudDetectionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionServiceApplication.class, args);
    }
}
