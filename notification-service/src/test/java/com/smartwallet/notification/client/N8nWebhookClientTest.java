package com.smartwallet.notification.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartwallet.notification.domain.NotificationPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level unit test for N8nWebhookClient.
 *
 * Uses OkHttp's MockWebServer to simulate n8n HTTP responses without
 * requiring a running n8n instance.
 */
@DisplayName("N8nWebhookClient — DispatchResult sealed type routing")
class N8nWebhookClientTest {

    private MockWebServer mockServer;
    private N8nWebhookClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(mockServer.url("/").toString());

        client = new N8nWebhookClient(
                builder,
                objectMapper,
                new SimpleMeterRegistry(),
                "test-token",
                3000,
                8000);

        // Inject the mock server URL via reflection (webhookUrl is @Value)
        try {
            var field = N8nWebhookClient.class.getDeclaredField("webhookUrl");
            field.setAccessible(true);
            field.set(client, mockServer.url("/webhook/test").toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("HTTP 200 → DispatchResult.Success with status code")
    void http200_returnsSuccess() {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        var result = client.dispatch(testPayload());

        // Java 21: pattern matching switch on sealed result type
        switch (result) {
            case N8nWebhookClient.DispatchResult.Success(var code) ->
                    assertThat(code).isEqualTo(200);
            case N8nWebhookClient.DispatchResult.RetryableFailure r ->
                    org.junit.jupiter.api.Assertions.fail("Expected Success, got RetryableFailure: " + r.reason());
            case N8nWebhookClient.DispatchResult.PermanentFailure p ->
                    org.junit.jupiter.api.Assertions.fail("Expected Success, got PermanentFailure: " + p.reason());
        }
    }

    @Test
    @DisplayName("HTTP 503 → DispatchResult.RetryableFailure")
    void http503_returnsRetryableFailure() {
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        var result = client.dispatch(testPayload());

        assertThat(result).isInstanceOf(N8nWebhookClient.DispatchResult.RetryableFailure.class);

        // Record pattern destructuring in instanceof
        if (result instanceof N8nWebhookClient.DispatchResult.RetryableFailure(var reason, var cause)) {
            assertThat(reason).contains("503");
        }
    }

    @Test
    @DisplayName("HTTP 429 → DispatchResult.RetryableFailure (rate-limited)")
    void http429_returnsRetryableFailure() {
        mockServer.enqueue(new MockResponse().setResponseCode(429).setBody("Too Many Requests"));

        var result = client.dispatch(testPayload());

        assertThat(result).isInstanceOf(N8nWebhookClient.DispatchResult.RetryableFailure.class);
    }

    @Test
    @DisplayName("HTTP 401 → DispatchResult.PermanentFailure (auth failure, no retry)")
    void http401_returnsPermanentFailure() {
        mockServer.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

        var result = client.dispatch(testPayload());

        assertThat(result).isInstanceOf(N8nWebhookClient.DispatchResult.PermanentFailure.class);

        if (result instanceof N8nWebhookClient.DispatchResult.PermanentFailure(var reason, var cause)) {
            assertThat(reason).contains("401");
        }
    }

    @Test
    @DisplayName("HTTP 400 → DispatchResult.PermanentFailure (bad payload, no retry)")
    void http400_returnsPermanentFailure() {
        mockServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

        var result = client.dispatch(testPayload());

        assertThat(result).isInstanceOf(N8nWebhookClient.DispatchResult.PermanentFailure.class);
    }

    @Test
    @DisplayName("HTTP 202 (Accepted) → DispatchResult.Success")
    void http202_returnsSuccess() {
        mockServer.enqueue(new MockResponse().setResponseCode(202));

        var result = client.dispatch(testPayload());

        assertThat(result).isInstanceOf(N8nWebhookClient.DispatchResult.Success.class);
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private NotificationPayload testPayload() {
        return new NotificationPayload(
                UUID.randomUUID(),
                "TRANSACTION_CREATED",
                UUID.randomUUID(),
                "acc-src-001",
                "user-001",
                new BigDecimal("5000"),
                "INR",
                "PEER_TO_PEER",
                "PENDING_FRAUD_CHECK",
                null, null, null,
                "INFO",
                "Test notification — unit test",
                Instant.now()
        );
    }
}
