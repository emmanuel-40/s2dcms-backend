package com.myproject.S2dcms.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.S2dcms.Exception.AiProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class AIComplaintService {

    private static final Logger logger = LoggerFactory.getLogger(AIComplaintService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String groqApiKey;
    private final String groqBaseUrl;
    private final String groqModel;

    public AIComplaintService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.api-key}") String groqApiKey,
            @Value("${spring.ai.openai.base-url}") String groqBaseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String groqModel
    ) {
        this.restClient = restClientBuilder
                .baseUrl(groqBaseUrl)
                .defaultHeader("Authorization", "Bearer " + groqApiKey)
                .build();
        this.objectMapper = objectMapper;
        this.groqApiKey = groqApiKey;
        this.groqBaseUrl = groqBaseUrl;
        this.groqModel = groqModel;
    }

    /**
     * Summarize a complaint into key points
     */
    public String summarizeComplaint(String complaintText) {
        try {
            logger.info("Starting AI complaint summarization");
            String systemPrompt = "You are a helpful assistant that summarizes student complaints. " +
                    "Extract and present the key points as bullet points (max 3-4 points), keep it very brief. " +
                    "Focus on the main issue, any specific details, and the desired outcome.";

            String userPrompt = "Please summarize this complaint:\n\n" + complaintText;

            String result = callGroqAPI(systemPrompt, userPrompt);
            logger.info("AI complaint summarization completed successfully");
            return result;
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected failure while generating an AI summary", e);
            throw AiProviderException.from(e);
        }
    }

    /**
     * Suggest a professional reply for department staff
     */
    public String suggestReply(String complaintText) {
        try {
            logger.info("Starting AI reply suggestion");
            String systemPrompt = "You are a helpful assistant that suggests professional responses " +
                    "to student complaints. Provide a polite, empathetic, and constructive response " +
                    "that addresses the student's concerns. Keep it very short (1 paragraph only), " +
                    "no letter format, no placeholders like [Your Name], just the actual response text. " +
                    "If the issue requires meeting a consultant or head of course, let them know the department will reach out to them and look into the problem.";

            String userPrompt = "Please suggest a professional reply to this complaint:\n\n" + complaintText;

            String result = callGroqAPI(systemPrompt, userPrompt);
            logger.info("AI reply suggestion completed successfully");
            return result;
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected failure while generating an AI reply suggestion", e);
            throw AiProviderException.from(e);
        }
    }

    /**
     * Help a student write a formal complaint based on their situation
     */
    public String writeComplaint(String situation) {
        try {
            logger.info("Starting AI complaint writing for situation: {}", situation);
            String systemPrompt = "You are a helpful assistant that helps students write complaints. " +
                    "Based on the student's described situation, provide a response in this exact format:\n" +
                    "TITLE: [a short, meaningful title for the complaint - max 10 words]\n" +
                    "CONTENT: [a concise, direct complaint that clearly states the issue - 1-2 paragraphs max, no letter format]. " +
                    "Use placeholders like [Course Name] and [Year] for course-specific information instead of guessing. " +
                    "Do not use placeholders like [Your Name] or other personal information.";

            String userPrompt = "Write a complaint about this situation:\n\n" + situation;

            String result = callGroqAPI(systemPrompt, userPrompt);
            logger.info("AI complaint writing completed successfully");
            return result;
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected failure while generating an AI complaint", e);
            throw AiProviderException.from(e);
        }
    }

    private String callGroqAPI(String systemPrompt, String userPrompt) {
        try {
            logger.info("Calling Groq API with model: {}", groqModel);

            Map<String, Object> requestBody = Map.of(
                    "model", groqModel,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );

            String response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String content = choices.get(0).path("message").path("content").asText();
                    logger.info("Successfully received response from Groq API");
                    return content;
                }
            }

            throw AiProviderException.unexpectedResponse();
        } catch (Exception e) {
            // One classification point for every provider failure: 429/5xx/4xx/connectivity all
            // land in AiProviderException, which alone decides what the client is allowed to see.
            throw AiProviderException.from(e);
        }
    }
}
