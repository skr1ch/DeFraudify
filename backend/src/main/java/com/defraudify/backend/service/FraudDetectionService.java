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
import org.springframework.core.io.ClassPathResource;
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

    private final GroqService groqService;
    private final WebSearchService webSearchService;

    public FraudDetectionService(GroqService groqService, WebSearchService webSearchService)
            throws ModelNotFoundException, MalformedModelException, IOException {

        this.groqService = groqService;
        this.webSearchService = webSearchService;

        logger.info("Initializing FraudDetectionService: Loading BERT model and tokenizer...");

        Path modelPath = copyResourceToTempFile("exported_model/bert-tiny-sms-spam-traced.pt");
        logger.info("Model file copied to temporary path: {}", modelPath.toAbsolutePath());

        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelPath)
                .optEngine("PyTorch")
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
        logger.info("PyTorch model loaded successfully.");

        Path vocabPath = copyResourceToTempFile("exported_model/vocab.txt");
        logger.info("Vocabulary file copied to temporary path: {}", vocabPath.toAbsolutePath());

        Vocabulary vocab = DefaultVocabulary.builder()
                .optMinFrequency(1)
                .addFromTextFile(vocabPath)
                .build();

        this.tokenizer = new BertFullTokenizer(vocab, true);
        logger.info("BERT tokenizer initialized successfully.");

        logger.info("FraudDetectionService initialization complete.");
    }

    private Path copyResourceToTempFile(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);

        if (!resource.exists()) {
            logger.error("Resource not found in classpath: {}", resourcePath);
            throw new FileNotFoundException("Resource not found: " + resourcePath);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            String fileName = Paths.get(resourcePath).getFileName().toString();
            File tempFile = File.createTempFile("djl_resource_", "_" + fileName);
            tempFile.deleteOnExit();

            logger.debug("Copying classpath resource '{}' to temporary file: {}", resourcePath, tempFile.getAbsolutePath());

            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                inputStream.transferTo(outputStream);
            }

            logger.debug("Successfully copied resource to temporary file.");
            return tempFile.toPath();
        } catch (IOException e) {
            logger.error("Failed to copy resource '{}' to temporary file.", resourcePath, e);
            throw e;
        }
    }

    public double getScamProbability(String message) throws TranslateException {
        logger.info("Calculating scam probability for message: {}", message);

        try (NDManager manager = NDManager.newBaseManager()) {
            List<String> tokens = tokenizer.tokenize(message);
            Vocabulary vocab = tokenizer.getVocabulary();

            long[] tokenIds = tokens.stream().mapToLong(vocab::getIndex).toArray();

            long padTokenId = vocab.getIndex("[PAD]");
            if (padTokenId == -1) {
                padTokenId = 0;
                logger.warn("[PAD] token not found in vocabulary, using ID 0 as fallback.");
            }

            long[] inputIds = new long[maxLength];
            long[] attentionMask = new long[maxLength];
            Arrays.fill(inputIds, padTokenId);
            Arrays.fill(attentionMask, 0L);

            int length = Math.min(tokenIds.length, maxLength);
            System.arraycopy(tokenIds, 0, inputIds, 0, length);
            Arrays.fill(attentionMask, 0, length, 1L);

            NDArray inputIdsArray = manager.create(inputIds).reshape(1, maxLength);
            NDArray attentionMaskArray = manager.create(attentionMask).reshape(1, maxLength);

            NDList input = new NDList(inputIdsArray, attentionMaskArray);

            NDList output = predictor.predict(input);

            if (output.isEmpty()) {
                throw new TranslateException("Model prediction returned an empty output.");
            }

            NDArray probabilities = output.get(0).softmax(1);
            double scamProbability = probabilities.getFloat(0, 1);

            // === Apply Heuristic-Based Score Boosting (Updated) ===
            double boostedScore = scamProbability;
            String lowerCaseMessage = message.toLowerCase();

            // --- NEW/ENHANCED BOOSTING FOR SHORT SCAM PHRASES ---
            if (lowerCaseMessage.contains("click here") && lowerCaseMessage.contains("claim")) {
                boostedScore = Math.min(boostedScore + 0.4, 1.0);
                logger.debug("Applied boost for 'click here' + 'claim'");
            }
            if (lowerCaseMessage.matches(".*\\b(free|prize|winner|congratulations)\\b.*\\$\\d+.*")) {
                boostedScore = Math.min(boostedScore + 0.35, 1.0);
                logger.debug("Applied boost for prize + $ pattern");
            }
            if (lowerCaseMessage.matches(".*\\b(urgent|act now|limited time)\\b.*")) {
                boostedScore = Math.min(boostedScore + 0.25, 1.0);
                logger.debug("Applied boost for urgency terms");
            }
            // --- END NEW BOOSTING ---

            // --- EXISTING & ENHANCED BOOSTING RULES ---

            // Universal Urgency & Threats (from Pasted_Text_1753618099621.txt)
            if (lowerCaseMessage.contains("urgent") || lowerCaseMessage.contains("asap") ||
                lowerCaseMessage.contains("immediately") || lowerCaseMessage.contains("risk") ||
                lowerCaseMessage.contains("locked") || lowerCaseMessage.contains("blocked") ||
                lowerCaseMessage.contains("expired") || lowerCaseMessage.contains("deadline") ||
                lowerCaseMessage.contains("suspended")) {
                boostedScore = Math.min(boostedScore + 0.2, 1.0);
                logger.debug("Applied boost for universal urgency/threat terms");
            }

            // Suspicious Links/Attachments (from Pasted_Text_1753618099621.txt)
            // Note: Actual links are removed by sanitization, but we can look for link-related words
            if (lowerCaseMessage.contains("click") || lowerCaseMessage.contains("link") ||
                lowerCaseMessage.contains("attachment") || lowerCaseMessage.contains("download")) {
                // Only boost if combined with other suspicious context
                if (lowerCaseMessage.contains("verify") || lowerCaseMessage.contains("secure") ||
                    lowerCaseMessage.contains("update") || lowerCaseMessage.contains("confirm")) {
                     boostedScore = Math.min(boostedScore + 0.2, 1.0);
                     logger.debug("Applied boost for suspicious link context");
                }
            }

            // Requests for Personal Data (from Pasted_Text_1753618099621.txt)
            if (lowerCaseMessage.contains("password") || lowerCaseMessage.contains("pin") ||
                lowerCaseMessage.contains("credentials") || lowerCaseMessage.contains("otp") ||
                lowerCaseMessage.contains("ssn") || lowerCaseMessage.contains("social security") ||
                lowerCaseMessage.contains("account number") || lowerCaseMessage.contains("id number")) {
                boostedScore = Math.min(boostedScore + 0.25, 1.0);
                logger.debug("Applied boost for requests for personal data");
            }

            // Common Email Subject Lines and Body Text Patterns (from Pasted_Text_1753618099621.txt)
            if (lowerCaseMessage.contains("session expired") || lowerCaseMessage.contains("verify identity") ||
                lowerCaseMessage.contains("secure account") || lowerCaseMessage.contains("change password") ||
                lowerCaseMessage.contains("suspicious activity") || lowerCaseMessage.contains("invoice due") ||
                lowerCaseMessage.contains("payment status") || lowerCaseMessage.contains("request")) {
                boostedScore = Math.min(boostedScore + 0.15, 1.0);
                logger.debug("Applied boost for common email scam phrases");
            }

            // BEC Specific Patterns (from Pasted_Text_1753618099621.txt)
            if (lowerCaseMessage.contains("due to the current situation") || lowerCaseMessage.contains("de-activation") ||
                lowerCaseMessage.contains("password check required")) {
                boostedScore = Math.min(boostedScore + 0.2, 1.0);
                logger.debug("Applied boost for BEC specific phrases");
            }

            // Previously existing rules (slightly adjusted weights)
            if (lowerCaseMessage.contains("pay now") || lowerCaseMessage.contains("pay ₹") || lowerCaseMessage.contains("pay rs")) {
                boostedScore = Math.min(boostedScore + 0.3, 1.0); // Kept high
            }
            if (lowerCaseMessage.contains("challan")) {
                boostedScore = Math.min(boostedScore + 0.15, 1.0);
            }
            if (lowerCaseMessage.contains("tech support") || lowerCaseMessage.contains("caller claiming")) {
                boostedScore = Math.min(boostedScore + 0.25, 1.0); // Kept
            }
            if (lowerCaseMessage.contains("remote access") || lowerCaseMessage.contains("anydesk") || lowerCaseMessage.contains("teamviewer")) {
                boostedScore = Math.min(boostedScore + 0.2, 1.0); // Kept
            }
            if (lowerCaseMessage.contains("credit card") || lowerCaseMessage.contains("debit card") || lowerCaseMessage.contains("financial information")) {
                boostedScore = Math.min(boostedScore + 0.2, 1.0); // Kept
            }
            if (lowerCaseMessage.contains("what should i do") || lowerCaseMessage.contains("feel pressured")) {
                boostedScore = Math.min(boostedScore + 0.15, 1.0); // Kept
            }

            // Message length adjustment (slightly stronger for very short or very long)
            if (message.length() < 20) { // Very short, might be a quick scam phrase
                 boostedScore = Math.min(boostedScore + 0.1, 1.0);
                 logger.debug("Applied boost for very short message length");
            } else if (message.length() > 300) { // Very long, might be a detailed user report (reduce boost)
                // Reduce boost slightly for very long messages, unless they contain strong scam keywords
                // This is a nuanced adjustment; we mostly rely on keywords.
                // The original +0.1 for >200 is kept but can be overridden by stronger rules.
                if (!(lowerCaseMessage.contains("urgent") || lowerCaseMessage.contains("click") || lowerCaseMessage.contains("password") || lowerCaseMessage.contains("pay"))) {
                     // If it's long but doesn't have strong keywords, reduce the length-based boost
                     // This is implicit in not adding the +0.1 if no strong keywords are present.
                } else {
                    // If it's long AND has strong keywords, still apply the original boost
                    boostedScore = Math.min(boostedScore + 0.1, 1.0);
                }
            }


            logger.info("Original ML Score: {}, Boosted Scam Score: {}", scamProbability, boostedScore);
            return boostedScore;

        } catch (Exception e) {
            logger.error("An error occurred during ML inference for message: {}", message, e);
            throw new TranslateException("Failed to calculate scam probability due to an internal error.", e);
        }
    }

    public Mono<FraudAnalysisResponse> performFullAnalysis(String message) {
        logger.info("Initiating full fraud analysis workflow for message: {}", message);

        double scamScore;
        try {
            scamScore = getScamProbability(message);
        } catch (Exception e) {
            logger.error("ML Inference failed for message: {}", message, e);
            return Mono.error(new RuntimeException("Machine Learning inference failed.", e));
        }

        Mono<String> explanationMono = groqService.generateExplanation(message, scamScore)
                .onErrorReturn("Unable to generate explanation at this time. Please try again later.");

        Mono<List<String>> relatedLinksMono = webSearchService.searchForSimilarIncidents(message, 5)
                .onErrorReturn(Collections.emptyList());

        return Mono.zip(explanationMono, relatedLinksMono)
                .map(tuple -> {
                    String explanation = tuple.getT1();
                    List<String> relatedLinks = tuple.getT2();
                    return new FraudAnalysisResponse(message, scamScore, explanation, relatedLinks);
                });
    }
}