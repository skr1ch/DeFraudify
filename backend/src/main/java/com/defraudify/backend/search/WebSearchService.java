package com.defraudify.backend.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

@Service
public class WebSearchService {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchService.class);

    private final WebClient webClient;
    private final String apiKey;
    private final String searchEngineId;

    // Inject the Google Search API key and Search Engine ID from application.properties
    public WebSearchService(@Value("${google.search.api.key}") String apiKey,
                            @Value("${google.search.engine.id}") String searchEngineId) {
        this.apiKey = apiKey;
        this.searchEngineId = searchEngineId;
        // --- CORRECTED BASE URL: Removed trailing spaces ---
        this.webClient = WebClient.builder()
                .baseUrl("https://www.googleapis.com") // <-- NO trailing spaces
                .build();
        // --- END CORRECTION ---
    }

    /**
     * Searches the web for incidents similar to the input message using Google Programmable Search Engine.
     *
     * @param message The user's input message.
     * @param maxResults The maximum number of relevant links to return.
     * @return A Mono containing a list of URLs (Strings) to relevant search results.
     */
    public Mono<List<String>> searchForSimilarIncidents(String message, int maxResults) {
        logger.info("Initiating web search using Google PSE for message: '{}'", message);

        // --- IMPROVED SEARCH QUERY CONSTRUCTION ---
        logger.info("Constructing search query for message: '{}'", message);

        // 1. Basic sanitization (keep essential characters for search)
        String sanitizedMessage = message.replaceAll("[^a-zA-Z0-9\\s\"'\\-_:]", " ").trim();
        String lowerCaseMessage = sanitizedMessage.toLowerCase();

        // 2. Build a targeted query using StringBuilder for efficiency
        StringBuilder specificQuery = new StringBuilder();

        // 3. Add key elements from the user's message (sanitized, first part)
        if (!sanitizedMessage.isEmpty()) {
            // Prioritize the beginning of the message for core context
            String[] words = sanitizedMessage.split("\\s+");
            // Limit core phrase length significantly (e.g., first 10-15 words)
            int wordsToAdd = Math.min(words.length, 12);
            for (int i = 0; i < wordsToAdd; i++) {
                specificQuery.append(words[i]).append(" ");
            }
        }

        // 4. Append specific scam type keywords based on message content
        // --- Tech Support Scams ---
        if (lowerCaseMessage.contains("tech support") || lowerCaseMessage.contains("microsoft") || lowerCaseMessage.contains("caller") || lowerCaseMessage.contains("computer") || lowerCaseMessage.contains("windows")) {
            specificQuery.append("tech support scam microsoft impersonation windows ");
        }
        // --- Remote Access Scams ---
        if (lowerCaseMessage.contains("remote access") || lowerCaseMessage.contains("teamviewer") || lowerCaseMessage.contains("anydesk") || lowerCaseMessage.contains("download") || lowerCaseMessage.contains("install")) {
            specificQuery.append("remote access scam teamviewer anydesk fraud ");
        }
        // --- Financial Scams ---
        if (lowerCaseMessage.contains("credit card") || lowerCaseMessage.contains("debit card") || lowerCaseMessage.contains("financial information") || lowerCaseMessage.contains("payment") || lowerCaseMessage.contains("fee") || lowerCaseMessage.contains("rs") || lowerCaseMessage.contains("rupees") || lowerCaseMessage.contains("pay")) {
            specificQuery.append("credit card scam payment request fraud rupees ");
        }
        // --- Generic Phishing/Account Scams ---
        if (lowerCaseMessage.contains("login") || lowerCaseMessage.contains("account") || lowerCaseMessage.contains("verify") || lowerCaseMessage.contains("credentials") || lowerCaseMessage.contains("password") || lowerCaseMessage.contains("otp")) {
             specificQuery.append("phishing scam account verification fraud password ");
        }
        // --- Urgency/Pressure Tactics ---
        if (lowerCaseMessage.contains("urgent") || lowerCaseMessage.contains("immediately") || lowerCaseMessage.contains("risk") || lowerCaseMessage.contains("crash") || lowerCaseMessage.contains("locked") || lowerCaseMessage.contains("blocked")) {
             specificQuery.append("urgent scam pressure tactics warning ");
        }
        // --- Government/Official Impersonation ---
        if (lowerCaseMessage.contains("irs") || lowerCaseMessage.contains("income tax") || lowerCaseMessage.contains("government") || lowerCaseMessage.contains("department") || lowerCaseMessage.contains("challan")) {
             specificQuery.append("irs scam government impersonation challan fraud ");
        }

        // 5. Add general advice/search terms to find relevant resources
        specificQuery.append("how to report what to do consumer advice");

        // 6. Finalize the query string
        String rawSearchQuery = specificQuery.toString().trim();
        logger.debug("Raw constructed search query (before truncation): {}", rawSearchQuery);

        // 7. Final Truncation: Keep it under 200-250 chars for better API performance and relevance
        String finalSearchQuery;
        if (rawSearchQuery.length() > 220) {
             finalSearchQuery = rawSearchQuery.substring(0, 220).trim();
        } else {
             finalSearchQuery = rawSearchQuery;
        }
        logger.info("Final constructed search query: {}", finalSearchQuery); // Log the final query
        // --- END IMPROVED SEARCH QUERY CONSTRUCTION ---

        logger.info("Making GET request to Google Custom Search API...");
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/customsearch/v1")
                        .queryParam("key", this.apiKey)
                        .queryParam("cx", this.searchEngineId)
                        .queryParam("q", finalSearchQuery) // <-- Use the improved, final query
                        .queryParam("num", Math.min(maxResults, 10)) // Google API limit per request
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(this::extractUrlsFromGoogleResponse)
                .doOnSuccess(urls -> logger.info("Successfully retrieved {} search results from Google PSE.", urls.size()))
                .onErrorResume(WebClientResponseException.class, ex -> {
                    logger.error("Google PSE API error (Status: {}): {}", ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
                    return Mono.just(new ArrayList<>());
                })
                .onErrorReturn(new ArrayList<>()); // Fallback for other unexpected errors
    }


    /**
     * Extracts URLs from the Google Custom Search API JSON response.
     *
     * @param response The response Map from the Google API.
     * @return A list of URLs (Strings).
     */
    @SuppressWarnings("unchecked")
    private List<String> extractUrlsFromGoogleResponse(Map<String, Object> response) {
        logger.debug("Starting to extract URLs from Google PSE response map");
        // === ADD THIS LOGGING ===
        logger.debug("Full Google PSE API Response received: {}", response); // Log the entire response structure
        // Check for common error fields in the response
        if (response.containsKey("error")) {
            Object errorObj = response.get("error");
            logger.error("Google PSE API returned an error object in the response body: {}", errorObj);
            // Try to extract specific error details
            if (errorObj instanceof Map) {
                Map<String, Object> errorMap = (Map<String, Object>) errorObj;
                Object message = errorMap.get("message");
                Object code = errorMap.get("code");
                Object status = errorMap.get("status");
                logger.error("Google PSE API Error Details - Code: {}, Status: {}, Message: {}", code, status, message);
            }
            return new ArrayList<>(); // Return empty list on API-level error in response body
        }
        // === END ADDITION ===

        try {
            Object itemsObj = response.get("items");
            if (!(itemsObj instanceof List)) {
                // --- IMPROVED LOGGING ---
                if (itemsObj == null) {
                    logger.info("No 'items' found in Google PSE response. The search might have yielded no results. Full response keys: {}", response.keySet());
                    // Log searchInformation if present for debugging query performance
                    Object searchInfo = response.get("searchInformation");
                    if (searchInfo instanceof Map) {
                         logger.info("Search Information: {}", searchInfo);
                         Object totalResults = ((Map<String, Object>) searchInfo).get("totalResults");
                         logger.info("Total Results reported by API: {}", totalResults);
                    }
                } else {
                    logger.warn("'items' is not a List in Google PSE response. Type: {}, Value: {}", itemsObj.getClass().getName(), itemsObj);
                }
                // --- END IMPROVED LOGGING ---
                return new ArrayList<>();
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) itemsObj;
            List<String> urls = results.stream()
                    .map(result -> (String) result.get("link"))
                    .filter(url -> url != null && !url.isEmpty())
                    .limit(5) // Limits results extracted here to 5
                    .collect(Collectors.toList());

            logger.debug("Successfully extracted {} URLs from Google PSE response.", urls.size());
            // === ADD THIS LOGGING ===
            if (logger.isDebugEnabled()) {
                logger.debug("Extracted URLs: {}", urls);
            }
            // === END ADDITION ===
            return urls;
        } catch (ClassCastException | NullPointerException e) {
            logger.error("Critical error parsing Google PSE response: {}", e.getMessage(), e);
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Unexpected error during Google PSE response parsing: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
}