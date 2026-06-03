package com.smartwallet.notification.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwallet.notification.domain.NotificationPayload;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;

/**
 * HTTP client for the n8n webhook endpoint.
 *
 * Uses Spring Boot 3.2's {@link RestClient} — the modern, fluent, synchronous
 * replacement for RestTemplate. On Java 21 with virtual threads enabled, every
 * blocking I/O call (TCP connect, reading the response body) parks the virtual
 * thread rather than blocking a platform thread, giving near-zero overhead for
 * hundreds of concurrent webhook dispatches.
 *
 * Returns a sealed {@link DispatchResult} — a Java 21 sealed interface hierarchy
 * that models success, retryable failure, and permanent failure explicitly in
 * the type system. Callers use pattern matching for switch to handle all cases
 * without null checks or exception catching at the call site.
 */
@Component
public class N8nWebhookClient implements WebhookClient {

    private static final Logger log = LoggerFactory.getLogger(N8nWebhookClient.class);

    private final RestClient   restClient;
    private final ObjectMapper objectMapper;
    private final Counter      successCounter;
    private final Counter      failureCounter;
    private final Timer        latencyTimer;

    @Value("${notification.n8n.webhook-url}")
    private String webhookUrl;

    public N8nWebhookClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${notification.n8n.auth-token}") String authToken,
            @Value("${notification.n8n.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${notification.n8n.read-timeout-ms:8000}") int readTimeoutMs) {

        this.objectMapper = objectMapper;

        /*
         * RestClient is built once and reused — it is thread-safe.
         *
         * On Java 21 + virtual threads:
         *   The underlying SimpleClientHttpRequestFactory uses JDK's HttpURLConnection
         *   which parks the virtual thread during connect/read waits. For high-throughput
         *   scenarios, swap to the JDK HttpClient factory (JdkClientHttpRequestFactory)
         *   which uses the JDK 11+ async HTTP client under the hood.
         *
         * In production: use JdkClientHttpRequestFactory with HttpClient.newBuilder()
         *   .executor(Executors.newVirtualThreadPerTaskExecutor()) for maximum efficiency.
         */
        this.restClient = restClientBuilder
                .defaultHeader("Authorization", "Bearer " + authToken)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Source", "smart-wallet-notification-service")
                .build();

        this.successCounter = Counter.builder("n8n.webhook.dispatch.success")
                .description("Successful n8n webhook dispatches")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("n8n.webhook.dispatch.failure")
                .description("Failed n8n webhook dispatches")
                .register(meterRegistry);
        this.latencyTimer = Timer.builder("n8n.webhook.dispatch.latency")
                .description("n8n webhook round-trip latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY DISPATCH METHOD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * POSTs the payload to the n8n webhook and returns a typed {@link DispatchResult}.
     *
     * Never throws — all exceptions are mapped to the appropriate DispatchResult subtype.
     * Callers should use pattern matching for switch on the returned result.
     *
     * @param payload  the notification payload to dispatch
     * @return         DispatchResult.Success | DispatchResult.RetryableFailure | DispatchResult.PermanentFailure
     */
    public DispatchResult dispatch(NotificationPayload payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Serialisation failure is permanent — retrying won't help
            return new DispatchResult.PermanentFailure(
                    "Serialisation failed: " + e.getMessage(), e);
        }

        return latencyTimer.record(() -> executePost(json, payload.notificationId().toString()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL
    // ─────────────────────────────────────────────────────────────────────────

    private DispatchResult executePost(String jsonBody, String notificationId) {
        try {
            /*
             * RestClient fluent API — clear, readable, no template method weirdness.
             *
             * Virtual thread behaviour on this call:
             *   1. .post().uri(webhookUrl) — synchronous, instant
             *   2. .body(jsonBody)         — builds request, instant
             *   3. .retrieve()             — opens TCP connection, PARKS virtual thread
             *   4. .toBodilessEntity()     — reads response headers + body, PARKS
             *
             * While parked, the carrier (platform) thread picks up another virtual thread.
             * The calling thread is not blocked — the JVM scheduler resumes it after I/O.
             */
            var response = restClient.post()
                    .uri(webhookUrl)
                    .body(jsonBody)
                    .retrieve()
                    .toBodilessEntity();

            HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());

            if (status.is2xxSuccessful()) {
                successCounter.increment();
                log.info("Webhook dispatched [notificationId={}] → HTTP {}",
                        notificationId, status.value());
                return new DispatchResult.Success(status.value());
            }

            // 3xx/4xx/5xx outside 2xx — categorise by retryability
            return categoriseHttpError(status, notificationId);

        } catch (RestClientResponseException e) {
            // HTTP error responses — RestClient throws this for 4xx/5xx by default
            failureCounter.increment();
            HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
            return categoriseHttpError(status, notificationId);

        } catch (Exception e) {
            failureCounter.increment();
            // Network-level errors: timeout, connection refused, DNS failure
            boolean isTimeout = e.getCause() instanceof SocketTimeoutException
                    || e.getMessage() != null && e.getMessage().contains("timeout");

            String reason = "Network error: " + e.getMessage();
            log.warn("Webhook dispatch error [notificationId={}]: {}", notificationId, reason);

            return isTimeout
                    ? new DispatchResult.RetryableFailure(reason, e)
                    : new DispatchResult.PermanentFailure(reason, e);
        }
    }

    private DispatchResult categoriseHttpError(HttpStatus status, String notificationId) {
        /*
         * Java 21 switch expression on HttpStatus.Series — clean, exhaustive mapping.
         *
         * Retryable:   5xx server errors (n8n may be temporarily overloaded)
         *              429 Too Many Requests (rate-limited, back off and retry)
         * Permanent:   4xx client errors except 429 (bad payload, auth failure —
         *              retrying will produce the same result)
         */
        String reason = "HTTP " + status.value() + " " + status.getReasonPhrase();
        log.warn("Webhook non-2xx [notificationId={}]: {}", notificationId, reason);

        return switch (status) {
            case TOO_MANY_REQUESTS         -> new DispatchResult.RetryableFailure(reason, null);
            case HttpStatus s when s.is5xxServerError() ->
                    new DispatchResult.RetryableFailure(reason, null);
            default                        -> new DispatchResult.PermanentFailure(reason, null);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEALED RESULT TYPE — Java 21 sealed interface hierarchy
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Typed result of a webhook dispatch attempt.
     *
     * Using a sealed interface instead of throwing exceptions means:
     *   1. The compiler forces callers to handle every outcome.
     *   2. No try/catch boilerplate at the call site.
     *   3. The retryability decision is encoded in the TYPE, not in catch clauses.
     *
     * Usage at call sites (OutboxScheduler, NotificationConsumer):
     *
     *   switch (client.dispatch(payload)) {
     *       case DispatchResult.Success(var httpStatus) ->
     *           outboxEntry.markSent();
     *       case DispatchResult.RetryableFailure(var reason, var cause) ->
     *           outboxEntry.markFailed(reason, backoffBase);
     *       case DispatchResult.PermanentFailure(var reason, var cause) ->
     *           outboxEntry.markFailed(reason + " [PERMANENT]", MAX_RETRIES);
     *   }
     */
    public sealed interface DispatchResult
            permits DispatchResult.Success,
                    DispatchResult.RetryableFailure,
                    DispatchResult.PermanentFailure {

        /** Webhook accepted the payload (2xx). */
        record Success(int httpStatusCode) implements DispatchResult {}

        /**
         * Transient failure — safe to retry after back-off.
         * Examples: 5xx, 429, SocketTimeoutException.
         */
        record RetryableFailure(String reason, Throwable cause) implements DispatchResult {}

        /**
         * Permanent failure — retrying will not succeed.
         * Examples: 400 Bad Request, 401 Unauthorized, serialisation error.
         */
        record PermanentFailure(String reason, Throwable cause) implements DispatchResult {}
    }
}
