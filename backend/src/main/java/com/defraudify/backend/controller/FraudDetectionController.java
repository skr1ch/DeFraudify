package com.defraudify.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.defraudify.backend.dto.FraudAnalysisRequest;
import com.defraudify.backend.dto.FraudAnalysisResponse;

@RestController
@RequestMapping("/api") // Base path for all endpoints in this controller
public class FraudDetectionController {

    @GetMapping("/health")
    public String healthCheck() {
        return "Backend is up and running!";
    }

    @PostMapping("/analyze")
    public FraudAnalysisResponse analyze(@RequestBody FraudAnalysisRequest request) {
        
        String userMessage = request.getMessage();

        // --- Simulate Fraud Detection Logic ---
        // In the future, this will call your ML model (BERT/DJL)
        // For now, we'll use a simple heuristic or dummy logic.
        // Let's simulate a high score for messages containing certain keywords.
        double scamProbability = 0.1; // Default low probability
        String lowerMessage = userMessage.toLowerCase();
        
        if (lowerMessage.contains("urgent") || 
            lowerMessage.contains("challan") || 
            lowerMessage.contains("pay now") || 
            lowerMessage.contains("click here") ||
            lowerMessage.contains("win") ||
            lowerMessage.contains("lottery")) {
            scamProbability = 0.92; // High probability
        } else if (lowerMessage.contains("verify") || 
                   lowerMessage.contains("account") || 
                   lowerMessage.contains("password")) {
            scamProbability = 0.75; // Medium-High probability
        }

        boolean isScam = scamProbability > 0.7;
        // --- End of Simulated Logic ---

        // --- Simulate LLM Explanation ---
        // In the future, this will call the Gemini API.
        // For now, we provide a basic template.
        String explanation;
        if (isScam) {
             explanation = "This message uses urgency and fear (e.g., 'pending challan', 'pay now') to pressure you into acting quickly. Scammers often use such tactics.";
        } else {
             explanation = "This message does not exhibit common scam characteristics based on keywords. However, always be cautious with unsolicited messages.";
        }
        // --- End of Simulated Explanation ---

        // Create and return the structured response
        return new FraudAnalysisResponse(userMessage, scamProbability, isScam, explanation);
    }
}