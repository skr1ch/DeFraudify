package com.defraudify.backend.groq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@Service
public class GroqService {

    private static final Logger logger = LoggerFactory.getLogger(GroqService.class);

    private final WebClient webClient;
    private final String apiKey;

    // Inject the API key from application.properties
    public GroqService(@Value("${groq.api.key}") String apiKey) {
        this.apiKey = apiKey;
        // Initialize WebClient with the base URL for the Groq API
        // Groq often uses an OpenAI-compatible endpoint
        // FIXED: Removed trailing spaces from the URL
        this.webClient = WebClient.builder()
                .baseUrl("https://api.groq.com/openai/v1/chat/completions") // <-- NO trailing spaces
                .build();
    }

    /**
     * Generates an explanation for why a message is considered scam or not using Groq.
     *
     * @param message The user's input message.
     * @param scamScore The probability score calculated by the BERT model.
     * @return A Mono containing the explanation string from Groq.
     */
    public Mono<String> generateExplanation(String message, double scamScore) {
        logger.info("Generating explanation using Groq for message: '{}'", message);

        // --- REFINED PROMPT LOGIC FOR CONSISTENT FORMAT ---
        // Goal: Always produce a response with "Likely Scam Type", "Key Concerns", and "Recommended Actions".

        String prompt = String.format(
            "Analyze the following message:\n\"%s\"\n\n" +
            "Scam Probability Score: %.2f\n\n" +
            "Based on the message content and the score, provide your analysis in EXACTLY the following format. Do not use markdown or add extra headings:\n\n" +
            "Likely Scam Type: [Identify the most probable type of scam based on the message and score, e.g., Phishing, Tech Support Scam, Lottery Scam, User Report, General Inquiry, etc. If the score is very low and it seems safe, state 'Unlikely to be Scam' or 'Safe/Legitimate'].\n\n" +
            "Key Concerns:\n" +
            "*   [List 2-3 specific concerns derived from the message content and the scam score. If the score is low and it seems safe, list 1-2 reasons why it appears safe or neutral.]\n" +
            "*   [...]\n\n" +
            "Recommended Actions:\n" +
            "*   [Provide 2-3 clear, actionable steps the user should take based on the analysis. For low-risk/safe messages, provide general good practices.]\n" +
            "*   [...]\n\n" +
            "Ensure the response strictly follows this format.",
            message, scamScore
        );
        // --- END REFINED PROMPT LOGIC ---

        logger.debug("Constructed prompt for Groq API: {}", prompt);

        // 2. Create the request body structure for the Groq API (OpenAI compatible)
        Map<String, Object> requestBody = new HashMap<>();
        // Use a suitable Groq model, e.g., llama3-8b-8192 or mixtral-8x7b-32768
        requestBody.put("model", "llama3-8b-8192");
        requestBody.put("temperature", 0.5); // Slightly lower for more focused responses
        requestBody.put("max_tokens", 250); // Increased limit for the structured response

        // Create the message object for the conversation
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);

        // Add the message to the request body
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(userMessage);
        requestBody.put("messages", messages);

        logger.debug("Constructed request body for Groq API: {}", requestBody);

        // 3. Make the asynchronous POST request using WebClient
        logger.info("Making POST request to Groq API...");
        return webClient.post()
                .uri("") // Base URL already includes the path
                // Add the Authorization header with the Bearer token
                .header("Authorization", "Bearer " + this.apiKey)
                // Set the Content-Type header
                .header("Content-Type", "application/json")
                // Send the request body
                .bodyValue(requestBody)
                // Retrieve the response body as a Map
                .retrieve()
                // Add specific error handling for non-2xx responses
                .onStatus(status -> {
                    logger.warn("Groq API returned non-2xx status code: {}", status);
                    return status.isError(); // Handle 4xx and 5xx errors
                }, response -> {
                    logger.error("Groq API error response status: {}", response.statusCode());
                    // Log the error response body if possible
                    return response.bodyToMono(String.class)
                            .doOnNext(errorBody -> logger.error("Groq API error response body: {}", errorBody))
                            .then(Mono.error(new RuntimeException("Groq API Error: " + response.statusCode())));
                })
                // Retrieve the response body as a Map
                .bodyToMono(Map.class)
                // Process the Map to extract the text from the response
                .map(this::extractTextFromResponse)
                // Handle potential errors gracefully
                .onErrorReturn("Unable to generate safeguarding advice at this time (Groq API Error).");
    }

    /**
     * Extracts the generated text from the Groq API response structure.
     *
     * @param response The response Map from the Groq API.
     * @return The generated text, or an error message if extraction fails.
     */
    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map<String, Object> response) {
        logger.debug("Starting to extract text from Groq response map");
        try {
            // Navigate the response structure:
            // response -> "choices" (List) -> [0] -> "message" -> "content" (String)
            Object choicesObj = response.get("choices");
            if (choicesObj == null) {
                logger.warn("'choices' key not found in Groq response: {}", response);
                return "No 'choices' found in Groq response.";
            }
            if (!(choicesObj instanceof List)) {
                logger.warn("'choices' is not a List in Groq response: {}", response);
                return "Invalid 'choices' format in Groq response.";
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) choicesObj;

            if (choices.isEmpty()) {
                logger.info("No choices found in Groq response: {}", response);
                return "No explanation choices found in Groq response.";
            }

            Map<String, Object> firstChoice = choices.get(0);
            if (firstChoice == null) {
                logger.warn("First choice is null in Groq response: {}", response);
                return "First choice is null.";
            }

            Object messageObj = firstChoice.get("message");
            if (messageObj == null) {
                logger.warn("'message' key not found in first choice: {}", firstChoice);
                return "No message found in the first choice.";
            }
            if (!(messageObj instanceof Map)) {
                logger.warn("'message' is not a Map in first choice: {}", firstChoice);
                return "Invalid 'message' format in first choice.";
            }
            Map<String, Object> message = (Map<String, Object>) messageObj;

            Object contentObj = message.get("content");
            if (contentObj == null) {
                logger.warn("'content' key not found in message: {}", message);
                return "No content found in the message.";
            }
            if (!(contentObj instanceof String)) {
                logger.warn("'content' is not a String in message: {}", message);
                return "Invalid 'content' format in message.";
            }

            String content = (String) contentObj;

            if (content.trim().isEmpty()) {
                logger.info("Generated explanation content is empty: '{}'", content);
                return "Generated safeguarding advice content is empty.";
            }

            String finalText = content.trim();
            logger.debug("Successfully extracted text from response: {}", finalText);
            return finalText;
        } catch (ClassCastException | NullPointerException e) {
            logger.error("Critical error parsing Groq response structure: {}", e.getMessage(), e);
            return "Error parsing safeguarding advice from AI response (Structure Error).";
        } catch (Exception e) {
            logger.error("Unexpected error during response parsing: {}", e.getMessage(), e);
            return "Error parsing safeguarding advice from AI response (Unexpected Error).";
        }
    }
}