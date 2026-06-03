package com.smartwallet.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification Service entry point.
 *
 * Virtual threads are activated via application.yml:
 *   spring.threads.virtual.enabled: true
 *
 * This single property replaces all of:
 *   - server.tomcat.max-threads tuning
 *   - @Async executor pool configuration
 *   - Custom ThreadPoolTaskExecutor beans for Kafka listeners
 *
 * The JVM scheduler handles multiplexing thousands of concurrent virtual
 * threads onto a small number of carrier (platform) threads automatically.
 */
@SpringBootApplication
@EnableScheduling
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
