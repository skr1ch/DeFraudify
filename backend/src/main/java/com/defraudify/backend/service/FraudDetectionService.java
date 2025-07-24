package com.defraudify.backend.service;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

@Service
public class FraudDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionService.class);

    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;
    private final BertFullTokenizer tokenizer;
    private final int maxLength = 128; // Should match the max_length used during export

    public FraudDetectionService() throws ModelNotFoundException, MalformedModelException, IOException, URISyntaxException {
        logger.info("Loading BERT model and tokenizer...");

        // 1. Load the TorchScript model using DJL
        // Use Paths.get with a classpath URI. DJL should handle this correctly.
        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(Paths.get(ClassLoader.getSystemResource("exported_model/bert-tiny-sms-spam-traced.pt").toURI())) // <-- Use URI from ClassLoader
                .optEngine("PyTorch") // Specify the engine
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
        logger.info("Model loaded successfully.");

        // 2. Load the Vocabulary for the tokenizer
        Vocabulary vocab = DefaultVocabulary.builder()
                .optMinFrequency(1)
                // Use Paths.get with a classpath URI for the vocab file as well.
                .addFromTextFile(Paths.get(ClassLoader.getSystemResource("exported_model/vocab.txt").toURI())) // <-- Use URI from ClassLoader
                .build();
        logger.info("Vocabulary loaded.");

        // 3. Initialize the BERT Tokenizer
        // The second argument 'true' indicates lowercasing, which should match the model's training
        this.tokenizer = new BertFullTokenizer(vocab, true);
        logger.info("BERT tokenizer initialized.");
    }


    /**
     * Calculates the probability that a given message is a scam using the loaded BERT model.
     *
     * @param message The user's input message.
     * @return The probability (between 0.0 and 1.0) that the message is a scam.
     * @throws TranslateException If an error occurs during model inference.
     */
        public double getScamProbability(String message) throws TranslateException {
        logger.info("Calculating scam probability for message: {}", message);

        try (NDManager manager = NDManager.newBaseManager()) { // Use NDManager for automatic resource management

            // --- Preprocessing: Tokenization and Conversion to NDArray ---
            // 1. Tokenize the input message using the BERT tokenizer
            // This adds [CLS] and [SEP] tokens and converts to a list of strings.
            List<String> tokens = tokenizer.tokenize(message);
            logger.debug("Tokens: {}", tokens);

            // 2. Convert tokens to indices using the vocabulary
            Vocabulary vocab = tokenizer.getVocabulary();
            long[] tokenIds = tokens.stream().mapToLong(vocab::getIndex).toArray();
            logger.debug("Token IDs: {}", Arrays.toString(tokenIds));

            // 3. Prepare input tensors (input_ids and attention_mask)
            // Get the index of the [PAD] token (usually 0 for BERT)
            long padTokenId = vocab.getIndex("[PAD]");
            if (padTokenId == -1) {
                logger.warn("[PAD] token not found in vocabulary, assuming index 0.");
                padTokenId = 0;
            }

            // Create arrays filled with padding value
            long[] inputIdsArray = new long[maxLength];
            long[] attentionMaskArray = new long[maxLength];
            Arrays.fill(inputIdsArray, padTokenId);
            Arrays.fill(attentionMaskArray, 0L); // 0 for padding

            // Fill the arrays with actual token IDs and set attention mask to 1
            int seqLength = Math.min(tokenIds.length, maxLength);
            System.arraycopy(tokenIds, 0, inputIdsArray, 0, seqLength);
            Arrays.fill(attentionMaskArray, 0, seqLength, 1L); // 1 for real tokens

            // 4. Convert Java arrays to DJL NDArrays
            // Shape is (1, maxLength) for a batch size of 1
            NDArray inputIds = manager.create(inputIdsArray).reshape(1, maxLength);
            NDArray attentionMask = manager.create(attentionMaskArray).reshape(1, maxLength);
            logger.debug("Input IDs NDArray shape: {}", inputIds.getShape());
            logger.debug("Attention Mask NDArray shape: {}", attentionMask.getShape());

            // 5. Create the input NDList for the model
            NDList input = new NDList(inputIds, attentionMask);
            // --- End of Preprocessing ---

            // --- Model Inference ---
            // 6. Run the model prediction using the predictor
            NDList output = predictor.predict(input);
            logger.debug("Model raw output NDList size: {}", output.size());
            // --- End of Model Inference ---

            // --- Post-processing: Extract Probability ---
            // 7. Extract the logits from the output NDList
            // The model is expected to output logits for two classes: [Not Spam, Spam]
            if (output.isEmpty()) {
                 logger.error("Model prediction returned an empty NDList.");
                 throw new TranslateException("Model prediction failed: Empty output.");
            }
            NDArray logits = output.get(0); // Get the first (and likely only) output tensor
            logger.debug("Logits NDArray shape: {}", logits.getShape());

            // 8. Apply Softmax to convert logits to probabilities
            // Axis 1 is the class dimension (axis 0 is batch)
            NDArray probabilities = logits.softmax(1);
            logger.debug("Probabilities NDArray shape: {}", probabilities.getShape());
            logger.debug("Probabilities NDArray: {}", probabilities);

            // 9. Get the probability for the "Spam" class (index 1)
            // .getFloat(0, 1) gets the value from batch element 0, class 1
            double scamProbability = probabilities.getFloat(0, 1);
            // --- End of Post-processing ---

            logger.info("Scam probability calculated using BERT model: {}", scamProbability);
            return scamProbability;

        } catch (Exception e) {
            logger.error("Error during BERT model inference for message: {}", message, e);
            // Re-throw as TranslateException as expected by the controller
            throw new TranslateException("Failed to calculate scam probability for message: " + message, e);
        }
    }

    // Optional: Close resources when the service is destroyed
    // While Spring manages the service lifecycle, explicitly closing models
    // is good practice in long-running applications.
    // @PreDestroy
    // public void close() {
    //     if (predictor != null) {
    //         predictor.close();
    //     }
    //     if (model != null) {
    //         model.close();
    //     }
    // }
}