package com.smartwallet.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.path;

@Configuration
public class GatewayRoutesConfig {

    /**
     * Functional, compile-safe routing definitions using Spring Cloud Gateway Server MVC.
     * Synchronous under the hood, but runs with massive scale on Project Loom virtual threads.
     *
     * Configures:
     *   - /v1/transactions/** routed to transaction-service (port 8081) with a circuit breaker.
     *   - /admin/notifications/** & /api/v1/notifications/** routed to notification-service (port 8083) with a circuit breaker.
     *   - Failed downstream calls trigger the global Resilience4j circuit breaker fallback route (/fallback).
     */
    @Bean
    public RouterFunction<ServerResponse> gatewayRoutes() {
        return route("transaction_service")
                .route(path("/api/v1/transactions/**"), http("http://127.0.0.1:8081"))
                .filter(circuitBreaker("transactionCircuitBreaker", "/fallback"))
                .build()
                .and(route("notification_service")
                        .route(path("/api/v1/notifications/**"), http("http://127.0.0.1:8083"))
                        .filter(circuitBreaker("notificationCircuitBreaker", "/fallback"))
                        .build())
                .and(route("fraud_service")
                        .route(path("/api/v1/fraud/**"), http("http://127.0.0.1:8082"))
                        .filter(circuitBreaker("fraudCircuitBreaker", "/fallback"))
                        .build());
    }
}
