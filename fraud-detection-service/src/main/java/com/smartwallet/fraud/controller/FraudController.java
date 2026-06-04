package com.smartwallet.fraud.controller;

import com.smartwallet.fraud.domain.FraudLogEntity;
import com.smartwallet.fraud.repository.FraudLogRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/fraud")
public class FraudController {

    private final FraudLogRepository fraudLogRepository;

    public FraudController(FraudLogRepository fraudLogRepository) {
        this.fraudLogRepository = fraudLogRepository;
    }

    @GetMapping("/logs")
    public ResponseEntity<List<FraudLogEntity>> getFraudLogs() {
        List<FraudLogEntity> logs = fraudLogRepository.findAll()
                .stream()
                .sorted((a, b) -> b.getEvaluatedAt().compareTo(a.getEvaluatedAt()))
                .toList();
        return ResponseEntity.ok(logs);
    }
}
