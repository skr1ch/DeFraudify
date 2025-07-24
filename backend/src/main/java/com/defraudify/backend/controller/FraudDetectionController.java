package com.defraudify.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FraudDetectionController {

    @GetMapping("/health")
    public String healthCheck() {
        return "Backend is up and running!";
    }

    @PostMapping("/analyze")
    public String analyze(@RequestBody String message) {
        // For now, just echo the input message
        return "Received message: " + message;
    }
}