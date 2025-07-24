package com.defraudify.backend.dto;

public class FraudAnalysisRequest {
    private String message;

    // Default constructor
    public FraudAnalysisRequest() {}

    // Constructor
    public FraudAnalysisRequest(String message) {
        this.message = message;
    }

    // Getter and Setter
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}