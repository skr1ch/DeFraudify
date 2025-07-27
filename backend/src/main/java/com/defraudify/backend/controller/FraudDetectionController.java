package com.defraudify.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.defraudify.backend.dto.FraudAnalysisRequest;
import com.defraudify.backend.dto.FraudAnalysisResponse;
import com.defraudify.backend.service.FraudDetectionService;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000") // Adjust for your frontend URL or use "*" in dev
public class FraudDetectionController {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionController.class);

    private final FraudDetectionService fraudDetectionService;

    @Autowired
    public FraudDetectionController(FraudDetectionService fraudDetectionService) {
        this.fraudDetectionService = fraudDetectionService;
    }

    /**
     * Health check endpoint to verify if backend is running.
     */
    @GetMapping("/health")
    public String healthCheck() {
        return "Backend is up and running!";
    }

    /**
     * Endpoint to analyze fraud in a given message.
     *
     * @param request Contains the user message to analyze
     * @return FraudAnalysisResponse wrapped in a ResponseEntity
     */
    @PostMapping("/analyze")
    public Mono<ResponseEntity<FraudAnalysisResponse>> analyzeFraud(@RequestBody FraudAnalysisRequest request) {
        String inputMessage = request.getMessage();

        logger.info("Received fraud analysis request for message: '{}'", inputMessage);

        if (inputMessage == null || inputMessage.trim().isEmpty()) {
            logger.warn("Received empty or null message in request.");
            return Mono.just(ResponseEntity.badRequest().build());
        }

        return fraudDetectionService.performFullAnalysis(inputMessage)
                .map(response -> {
                    logger.info("Fraud analysis completed successfully.");
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    logger.error("Error occurred during fraud analysis: {}", e.getMessage(), e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }
}
