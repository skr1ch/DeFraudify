package com.defraudify.backend.dto;

import java.util.List; // Add this import

public class FraudAnalysisResponse {

    private String message;
    private double scamScore;
    private String explanation;
    private List<String> relatedLinks; // Add this field for web search results

    // Default constructor
    public FraudAnalysisResponse() {}

    // Constructor including relatedLinks
    // Ensure this constructor signature matches how it's called in FraudDetectionService
    public FraudAnalysisResponse(String message, double scamScore, String explanation, List<String> relatedLinks) {
        this.message = message;
        this.scamScore = scamScore;
        this.explanation = explanation;
        this.relatedLinks = relatedLinks; // Assign the new field
    }

    // Getters and Setters
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public double getScamScore() {
        return scamScore;
    }

    public void setScamScore(double scamScore) {
        this.scamScore = scamScore;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    // Getter and Setter for relatedLinks
    public List<String> getRelatedLinks() {
        return relatedLinks;
    }

    public void setRelatedLinks(List<String> relatedLinks) {
        this.relatedLinks = relatedLinks;
    }
}