package com.defraudify.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody; // Import the new GroqService
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.defraudify.backend.dto.FraudAnalysisRequest; // Import for reactive programming
import com.defraudify.backend.dto.FraudAnalysisResponse;
import com.defraudify.backend.groq.GroqService;
import com.defraudify.backend.service.FraudDetectionService; // Add this import

import reactor.core.publisher.Mono; // Add this import for Mono

@RestController
@RequestMapping("/api") // Base path for all endpoints in this controller
public class FraudDetectionController {

    // --- Dependency Injection ---
    private final FraudDetectionService fraudDetectionService;
    private final GroqService groqService; // Add this field

    // Constructor-based injection with @Autowired for both services
    @Autowired
    public FraudDetectionController(FraudDetectionService fraudDetectionService, GroqService groqService) {
        this.fraudDetectionService = fraudDetectionService;
        this.groqService = groqService; // Assign the GroqService field
    }
    // --- End of Dependency Injection ---

    @GetMapping("/health")
    public String healthCheck() {
        return "Backend is up and running!";
    }

    @PostMapping("/analyze")
    public Mono<FraudAnalysisResponse> analyze(@RequestBody FraudAnalysisRequest request) {
        String userMessage = request.getMessage();

        try {
            // --- 1. Call the Service for Fraud Detection (Synchronous) ---
            double scamProbability = fraudDetectionService.getScamProbability(userMessage);
            boolean isScam = scamProbability > 0.7; // Or your chosen threshold
            // --- End of Fraud Detection ---

            // --- 2. Call the Groq Service for Explanation (Asynchronous) ---
            // groqService.generateExplanation returns a Mono<String>
            Mono<String> explanationMono = groqService.generateExplanation(userMessage, scamProbability);
            // --- End of Groq Service Call ---

            // --- 3. Combine Results ---
            // Use Mono.map to transform the explanation Mono into the final response Mono
            // The 'explanation' parameter in map is the resolved string from explanationMono
            return explanationMono.map(explanation ->
                new FraudAnalysisResponse(userMessage, scamProbability, isScam, explanation)
            )
            // Handle potential errors from the asynchronous Groq call
            .onErrorReturn(new FraudAnalysisResponse(userMessage, scamProbability, isScam, "An error occurred generating the explanation using Groq."));
            // --- End of Combining Results ---

        } catch (Exception e) {
            // Handle errors from the synchronous fraud detection service
            System.err.println("Error in fraud detection or explanation generation: " + e.getMessage());
            e.printStackTrace(); // Log the stack trace for debugging
            // Return a basic error response using Mono.just for synchronous error path
            return Mono.just(new FraudAnalysisResponse(userMessage, 0.0, false, "An error occurred during initial analysis."));
        }
    }
}