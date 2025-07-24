package com.defraudify.backend.dto;

public class FraudAnalysisResponse {
    private String message;
    private double scamScore;
    private boolean isScam;
    private String explanation; // Will be populated by LLM later

    // Default constructor
    public FraudAnalysisResponse() {}

    // Constructor for easy creation
    public FraudAnalysisResponse(String message, double scamScore, boolean isScam, String explanation) {
        this.message = message;
        this.scamScore = scamScore;
        this.isScam = isScam;
        this.explanation = explanation;
    }

    // Getters and Setters
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public double getScamScore() { return scamScore; }
    public void setScamScore(double scamScore) { this.scamScore = scamScore; }

    public boolean isScam() { return isScam; }
    public void setScam(boolean scam) { isScam = scam; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}