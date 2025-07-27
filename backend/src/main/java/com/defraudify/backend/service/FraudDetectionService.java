package com.defraudify.backend.service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource; // Added import
import org.springframework.stereotype.Service;

import com.defraudify.backend.dto.FraudAnalysisResponse;
import com.defraudify.backend.groq.GroqService;
import com.defraudify.backend.search.WebSearchService;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.Vocabulary;
import ai.djl.modality.nlp.bert.BertFullTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import reactor.core.publisher.Mono;

@Service
public class FraudDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionService.class);

    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;
    private final BertFullTokenizer tokenizer;
    private final int maxLength = 128;

    // Use constructor injection instead of field injection (addresses SonarLint warnings)
    private final GroqService groqService;
    private final WebSearchService webSearchService;

    // Constructor for dependency injection
    public FraudDetectionService(GroqService groqService, WebSearchService webSearchService)
            throws ModelNotFoundException, MalformedModelException, IOException {

        this.groqService = groqService;
        this.webSearchService = webSearchService;

        logger.info("Initializing FraudDetectionService: Loading BERT model and tokenizer...");

        // --- Load the Traced PyTorch Model ---
        // Use the helper method to copy the model resource to a temporary file
        Path modelPath = copyResourceToTempFile("exported_model/bert-tiny-sms-spam-traced.pt");
        logger.info("Model file copied to temporary path: {}", modelPath.toAbsolutePath());

        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelPath) // Use the temporary file path
                .optEngine("PyTorch") // Specify the engine
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
        logger.info("PyTorch model loaded successfully.");

        // --- Load the Vocabulary for the Tokenizer ---
        // Use the helper method to copy the vocab resource to a temporary file
        Path vocabPath = copyResourceToTempFile("exported_model/vocab.txt");
        logger.info("Vocabulary file copied to temporary path: {}", vocabPath.toAbsolutePath());

        Vocabulary vocab = DefaultVocabulary.builder()
                .optMinFrequency(1)
                .addFromTextFile(vocabPath) // Use the temporary file path
                .build();

        this.tokenizer = new BertFullTokenizer(vocab, true); // true for lowercasing
        logger.info("BERT tokenizer initialized successfully.");

        logger.info("FraudDetectionService initialization complete.");
    }


    /**
     * Copies a resource file from the JAR/classpath to a temporary file.
     * This is necessary because DJL often requires a file path, not a classpath resource URI,
     * especially when running from a packaged JAR.
     *
     * @param resourcePath The path to the resource within the JAR (e.g., "exported_model/bert-tiny-sms-spam-traced.pt").
     * @return The Path to the created temporary file.
     * @throws IOException If the resource is not found or an error occurs during copying.
     */
    private Path copyResourceToTempFile(String resourcePath) throws IOException {
        // Use Spring's ClassPathResource for robust resource loading
        ClassPathResource resource = new ClassPathResource(resourcePath);

        // Check if the resource exists
        if (!resource.exists()) {
            logger.error("Resource not found in classpath: {}", resourcePath);
            throw new FileNotFoundException("Resource not found: " + resourcePath);
        }

        // Get the InputStream for the resource
        try (InputStream inputStream = resource.getInputStream()) {
            // Create a temporary file. The prefix and suffix help identify it.
            // deleteOnExit ensures cleanup when the JVM shuts down (basic cleanup).
            String fileName = Paths.get(resourcePath).getFileName().toString();
            File tempFile = File.createTempFile("djl_resource_", "_" + fileName);
            tempFile.deleteOnExit(); // Basic cleanup mechanism

            logger.debug("Copying classpath resource '{}' to temporary file: {}", resourcePath, tempFile.getAbsolutePath());

            // Copy the contents of the resource InputStream to the temporary file
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                inputStream.transferTo(outputStream);
            }

            logger.debug("Successfully copied resource to temporary file.");
            // Return the Path object representing the temporary file
            return tempFile.toPath();
        } catch (IOException e) {
            logger.error("Failed to copy resource '{}' to temporary file.", resourcePath, e);
            throw e; // Re-throw to propagate the error
        }
    }


    /**
     * Calculates the scam probability for a given message using the loaded BERT model.
     *
     * @param message The input text message to analyze.
     * @return The calculated scam probability (between 0.0 and 1.0).
     * @throws TranslateException If an error occurs during model inference.
     */
    public double getScamProbability(String message) throws TranslateException {
        logger.info("Calculating scam probability for message: {}", message);

        try (NDManager manager = NDManager.newBaseManager()) {
            // 1. Tokenize the input message using the BERT tokenizer
            List<String> tokens = tokenizer.tokenize(message);
            Vocabulary vocab = tokenizer.getVocabulary();

            // 2. Convert tokens to their corresponding IDs using the vocabulary
            long[] tokenIds = tokens.stream().mapToLong(vocab::getIndex).toArray();

            // 3. Handle the padding token ID ([PAD])
            long padTokenId = vocab.getIndex("[PAD]");
            if (padTokenId == -1) {
                padTokenId = 0; // Default fallback if [PAD] is not found
                logger.warn("[PAD] token not found in vocabulary, using ID 0 as fallback.");
            }

            // 4. Prepare input arrays (input_ids and attention_mask) for the model
            long[] inputIds = new long[maxLength];
            long[] attentionMask = new long[maxLength];
            Arrays.fill(inputIds, padTokenId);      // Fill input_ids with pad token ID
            Arrays.fill(attentionMask, 0L);         // Fill attention_mask with 0

            // 5. Copy actual token IDs and set corresponding attention mask values to 1
            int length = Math.min(tokenIds.length, maxLength);
            System.arraycopy(tokenIds, 0, inputIds, 0, length);
            Arrays.fill(attentionMask, 0, length, 1L); // Set mask to 1 for actual tokens

            // 6. Create NDArrays for input to the model
            NDArray inputIdsArray = manager.create(inputIds).reshape(1, maxLength);
            NDArray attentionMaskArray = manager.create(attentionMask).reshape(1, maxLength);

            // 7. Create the input NDList for the predictor
            NDList input = new NDList(inputIdsArray, attentionMaskArray);

            // 8. Perform prediction using the loaded model
            NDList output = predictor.predict(input);

            // 9. Check if the output is valid
            if (output.isEmpty()) {
                throw new TranslateException("Model prediction returned an empty output.");
            }

            // 10. Extract probabilities (apply softmax to logits)
            NDArray probabilities = output.get(0).softmax(1); // Apply softmax along class dimension (dim=1)

            // 11. Get the probability for the "scam" class (index 1)
            double scamProbability = probabilities.getFloat(0, 1); // Get value from batch 0, class 1

            // === Apply Heuristic-Based Score Boosting ===
            double boostedScore = scamProbability;
            String lowerCaseMessage = message.toLowerCase();

            // Boost based on common scam indicators in the message content
            if (lowerCaseMessage.contains("urgent") || lowerCaseMessage.contains("asap")) {
                boostedScore = Math.min(boostedScore + 0.2, 1.0);
            }
            if (lowerCaseMessage.contains("click") && lowerCaseMessage.contains("link")) {
                boostedScore = Math.min(boostedScore + 0.25, 1.0);
            }
            if (lowerCaseMessage.contains("pay now") || lowerCaseMessage.contains("pay ₹") || lowerCaseMessage.contains("pay rs")) {
                boostedScore = Math.min(boostedScore + 0.3, 1.0);
            }
            if (lowerCaseMessage.contains("challan")) { // Specific term often used in Indian scams
                boostedScore = Math.min(boostedScore + 0.15, 1.0);
            }
            if (lowerCaseMessage.contains("tech support") || lowerCaseMessage.contains("caller claiming")) {
                boostedScore = Math.min(boostedScore + 0.25, 1.0);
            }
            if (lowerCaseMessage.contains("remote access") || lowerCaseMessage.contains("anydesk") || lowerCaseMessage.contains("teamviewer")) {
                boostedScore = Math.min(boostedScore + 0.2, 1.0);
            }
            if (lowerCaseMessage.contains("credit card") || lowerCaseMessage.contains("debit card") || lowerCaseMessage.contains("financial information")) {
                boostedScore = Math.min(boostedScore + 0.2, 1.0);
            }
            if (lowerCaseMessage.contains("what should i do") || lowerCaseMessage.contains("feel pressured")) {
                // User describing being targeted/scammed
                boostedScore = Math.min(boostedScore + 0.15, 1.0);
            }
            // Boost based on message length (longer, detailed messages describing incidents might be genuine user reports)
            if (message.length() > 200) {
                boostedScore = Math.min(boostedScore + 0.1, 1.0);
            }

            logger.info("Original ML Score: {}, Boosted Scam Score: {}", scamProbability, boostedScore);
            return boostedScore; // Return the final boosted score

        } catch (Exception e) {
            logger.error("An error occurred during ML inference for message: {}", message, e);
            // Wrap any unexpected exception in TranslateException to match method signature
            throw new TranslateException("Failed to calculate scam probability due to an internal error.", e);
        }
    }

    /**
     * Performs the full fraud analysis: ML scoring, LLM explanation, and web search for related incidents.
     *
     * @param message The user's input message.
     * @return A Mono containing the complete FraudAnalysisResponse.
     */
    public Mono<FraudAnalysisResponse> performFullAnalysis(String message) {
        logger.info("Initiating full fraud analysis workflow for message: {}", message);

        // 1. Get the scam score from the ML model (synchronous call wrapped in Mono)
        double scamScore;
        try {
            scamScore = getScamProbability(message);
        } catch (Exception e) {
            logger.error("ML Inference failed for message: {}", message, e);
            // Return a Mono.error if the core ML step fails
            return Mono.error(new RuntimeException("Machine Learning inference failed.", e));
        }

        // 2. Generate explanation using GroqService (asynchronous)
        // onErrorReturn provides a fallback explanation if the LLM call fails
        Mono<String> explanationMono = groqService.generateExplanation(message, scamScore)
                .onErrorReturn("Unable to generate explanation at this time. Please try again later.");

        // 3. Search for related incidents using WebSearchService (asynchronous)
        // onErrorReturn provides a fallback (empty list) if the web search fails
        Mono<List<String>> relatedLinksMono = webSearchService.searchForSimilarIncidents(message, 5) // Request up to 5 links
                .onErrorReturn(Collections.emptyList());

        // 4. Combine the results from the asynchronous operations (explanation and links)
        // Mono.zip waits for both Monos to complete and combines their results into a tuple
        return Mono.zip(explanationMono, relatedLinksMono)
                // 5. Map the combined results (tuple) to the final FraudAnalysisResponse object
                .map(tuple -> {
                    String explanation = tuple.getT1(); // Get explanation from the first part of the tuple
                    List<String> relatedLinks = tuple.getT2(); // Get links from the second part of the tuple
                    // Create and return the response object
                    return new FraudAnalysisResponse(message, scamScore, explanation, relatedLinks);
                });
    }
}