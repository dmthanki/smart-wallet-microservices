package com.smartwallet.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Controller providing global Resilience4j fallbacks inside the API Gateway.
 */
@RestController
public class FallbackController {

    /**
     * Standard JSON error response returned when a downstream service is down or timing out.
     */
    @RequestMapping("/fallback")
    public ResponseEntity<Map<String, Object>> gatewayFallback() {
        Map<String, Object> response = Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Service Unavailable",
                "message", "The downstream microservice is temporarily unavailable or timed out. Please try again later.",
                "timestamp", Instant.now().toString()
        );
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
