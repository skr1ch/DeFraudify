# DeFraudify Backend

This Java-based backend is the analytical engine of the DeFraudify application. Its primary function is to assess text messages for potential fraud or scam content and provide users with a risk score, a clear explanation, and relevant resources. It achieves this by combining machine learning, artificial intelligence, and web search technologies.

## Core Functionality

The backend operates as a RESTful API, exposing endpoints (like `/api/analyze`) that accept text messages for evaluation. When a message is received, the backend orchestrates a multi-step analysis workflow:

1.  **Machine Learning Scoring:** The core fraud detection is powered by a fine-tuned Natural Language Processing (NLP) model. Specifically, a BERT (Bidirectional Encoder Representations from Transformers) model is used. This model has been trained on a dataset of messages labeled as 'spam' or 'ham' (legitimate). It processes the input text and outputs an initial probability score indicating how likely the message is to be fraudulent. This score is a numerical value between 0.0 (definitely not a scam) and 1.0 (definitely a scam).

2.  **Heuristic Enhancement:** To refine the initial ML score, the backend applies a set of heuristic rules. These rules look for specific keywords or phrases commonly associated with scams (e.g., "urgent", "click here", "pay now", "tech support", "won prize"). If these elements are detected, the initial score is boosted. This step helps to compensate for potential blind spots in the ML model and ensures common scam tactics are caught.

3.  **AI Explanation Generation:** The numerical `scamScore` and the original message are sent to the Groq API. Groq provides fast inference for Large Language Models (LLMs). An LLM (like Llama3) is prompted to interpret the score and the message content. It generates a human-readable explanation that includes:
    *   The likely type of scam.
    *   Key concerns identified in the message.
    *   Recommended actions for the user.
    This makes the analysis transparent and actionable for the user.

4.  **Related Resource Search:** Simultaneously, the backend formulates a search query based on the original message. This query is designed to find relevant articles, advice, or reports about the specific type of scam detected (or reported). This query is sent to the Google Programmable Search Engine (PSE) API.

5.  **Response Aggregation:** The results from the Groq API (explanation) and the Google PSE API (related links) are collected. Along with the original message and the final `scamScore` (from ML + Heuristics), they are packaged into a structured JSON response and sent back to the client that made the request.

### Key Components

*   **Spring Boot Application:** The foundation, handling HTTP requests, dependency injection, and application lifecycle.
*   **FraudDetectionController:** The entry point for API requests. It receives the message, validates it, and invokes the core analysis service.
*   **FraudDetectionService:** The central orchestrator. It manages the loading of the ML model, performs the initial ML inference, applies heuristic boosting, and coordinates calls to the Groq and Google PSE services. It aggregates the final results.
*   **GroqService:** Responsible for interacting with the Groq API. It constructs the prompt, makes the HTTP request, and parses the LLM's response to extract the explanation.
*   **WebSearchService:** Handles communication with the Google Programmable Search Engine API. It formulates the search query based on the message, makes the HTTP request, and parses the response to extract relevant URLs.
*   **Deep Java Library (DJL):** A library used to load and run the PyTorch BERT model within the Java environment. It abstracts away much of the complexity of direct ML framework integration.

## Data Flow for Fraud Analysis

1.  **Request:** A client sends a `POST` request to `http://<your-backend-url>/api/analyze` with a JSON body like `{"message": "..."}`.
2.  **Reception:** `FraudDetectionController` receives the request, extracts the message, and passes it to `FraudDetectionService`.
3.  **ML Inference:** `FraudDetectionService` uses DJL to tokenize the message and pass it to the loaded BERT model. The model returns an initial probability score.
4.  **Heuristic Boosting:** `FraudDetectionService` applies its rules to the message and the initial score, calculating a final `scamScore`.
5.  **Parallel Processing:**
    *   `FraudDetectionService` calls `GroqService.generateExplanation(message, scamScore)`.
    *   `FraudDetectionService` calls `WebSearchService.searchForSimilarIncidents(message, maxResults)`.
6.  **AI Explanation:** `GroqService` formats a prompt, sends it to the Groq API via `WebClient`, receives the JSON response, parses it, and returns the explanation text.
7.  **Web Search:** `WebSearchService` constructs a search query, sends it to the Google PSE API via `WebClient`, receives the JSON response, parses it, and returns a list of URLs.
8.  **Aggregation:** `FraudDetectionService` collects the original message, the final `scamScore`, the explanation from `GroqService`, and the links from `WebSearchService`.
9.  **Response:** `FraudDetectionController` takes the aggregated data and returns it as a JSON response to the client.

## Technologies Used

*   **Language:** Java 17
*   **Framework:** Spring Boot 3.x (for building the web application, dependency injection, and REST APIs)
*   **Build Tool:** Apache Maven (for project management and building)
*   **ML Framework Integration:** Deep Java Library (DJL) - Specifically, the PyTorch engine to load and run the BERT model.
*   **NLP Model:** A fine-tuned BERT model (exported as `pytorch_model.bin`).
*   **AI API:** Groq API (for fast LLM inference to generate explanations).
*   **Web Search API:** Google Programmable Search Engine (PSE) API.
*   **HTTP Client:** Spring WebClient (for asynchronous calls to external APIs).
*   **Reactive Programming:** Project Reactor (used by WebClient, helps manage asynchronous operations efficiently).

## API Endpoints

*   `POST /api/analyze`: The main endpoint for submitting a message for fraud analysis.
*   `GET /api/health`: A simple endpoint to check if the backend service is running.

## Configuration

The application requires API keys for external services. These are configured in `src/main/resources/application.properties`:

*   `groq.api.key`: Your Groq API key.
*   `google.search.api.key`: Your Google PSE API key.
*   `google.search.engine.id`: Your Google PSE Search Engine ID.

Ensure these are set correctly for the backend to function properly.

## Deployment Considerations

The backend is designed to be packaged as a standalone JAR file using `mvn clean package`. This JAR can then be run using `java -jar <filename>.jar`. It can be deployed to various cloud platforms (like Azure App Service, AWS EC2, or using Docker containers on platforms like Render or Azure Container Instances) that support running Java applications. Ensure environment variables are used for API keys in production environments.

