package com.smartwallet.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Central Spring configuration for the notification-service.
 *
 * Beans defined here:
 *   • RestClient.Builder  — pre-configured with base URL and timeout headers
 *   • ObjectMapper        — Jackson with Java 8 time module for Instant/Duration
 *   • KafkaListenerContainerFactory — manual-ack container for reliable offset commits
 *
 * @EnableScheduling activates the OutboxScheduler's @Scheduled methods.
 * Virtual thread execution of scheduled tasks is automatic when
 * spring.threads.virtual.enabled: true — no explicit executor wiring needed
 * in Spring Boot 3.2+.
 */
@Configuration
@EnableScheduling
public class NotificationConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ── ObjectMapper ──────────────────────────────────────────────────────────

    /**
     * Shared Jackson ObjectMapper configured for Java 21 record serialisation
     * and Instant/Duration support.
     *
     * Jackson 2.12+ handles records natively via the canonical constructor —
     * no @JsonCreator needed. Records with null components serialise as JSON null.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Serialize Instant as ISO-8601 string, not epoch array
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ── RestClient ────────────────────────────────────────────────────────────

    /**
     * RestClient.Builder bean — Spring Boot auto-configures a builder bean;
     * we customise it here with shared defaults.
     *
     * The built RestClient is stateless and thread-safe. When used on virtual
     * threads (the default in this service), each HTTP call parks the virtual
     * thread during I/O without blocking a platform thread.
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .defaultHeader("X-Service", "smart-wallet-notification");
    }

    // ── Kafka Listener Container Factory ─────────────────────────────────────

    /**
     * Configures the Kafka listener container for MANUAL_IMMEDIATE ack mode.
     *
     * MANUAL_IMMEDIATE means the Kafka offset is committed only when the
     * listener explicitly calls ack.acknowledge(). This is what gives us the
     * at-least-once delivery guarantee:
     *   write outbox row → attempt dispatch → update outbox → ack()
     *
     * If the JVM crashes after the DB write but before ack(), Kafka redelivers
     * the event on restart — the outbox row already exists, and the scheduler
     * will dispatch it on the next retry tick.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object>
    kafkaListenerContainerFactory(ConsumerFactory<String, Object> consumerFactory) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);  // one virtual thread per Kafka partition group

        // Manual acknowledgement — essential for the outbox pattern
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Observation for Micrometer tracing
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }
}
