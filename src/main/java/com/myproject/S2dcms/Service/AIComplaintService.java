package com.myproject.S2dcms.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.S2dcms.Exception.AiProviderException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AIComplaintService {

    private static final Logger logger = LoggerFactory.getLogger(AIComplaintService.class);

    private static final int MAX_ATTEMPTS = 3;

    private static final long BASE_BACKOFF_MS = 500L;
    private static final long MAX_JITTER_MS = 300L;

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
            @Value("${spring.ai.openai.chat.options.model}") String groqModel,
            @Value("${ai.request.connect-timeout:3s}") Duration connectTimeout,
            @Value("${ai.request.read-timeout:8s}") Duration readTimeout
    ) {
        /*
         * Fail fast on hung provider.
         */
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(groqBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + groqApiKey)
                .build();
        this.objectMapper = objectMapper;
        this.groqApiKey = groqApiKey;
        this.groqBaseUrl = groqBaseUrl;
        this.groqModel = groqModel;
    }

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

    @CircuitBreaker(name = "groq")
    private String callGroqAPI(String systemPrompt, String userPrompt) {
        int attempts = 0;
        Throwable lastError = null;

        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            try {
                logger.info("Calling Groq API with model: {} (attempt {}/{})", groqModel, attempts, MAX_ATTEMPTS);

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
            } catch (AiProviderException e) {
                /*
                 * Already classified; do not retry.
                 */
                throw e;
            } catch (HttpClientErrorException.TooManyRequests e) {
                lastError = e;
                long waitMs = parseRetryAfter(e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                if (attempts >= MAX_ATTEMPTS) {
                    logger.warn("Groq 429 rate limited — max attempts ({}) reached", MAX_ATTEMPTS);
                    throw AiProviderException.from(e);
                }
                logger.warn("Groq 429 rate limited — retrying in {}ms (attempt {}/{})",
                        waitMs, attempts, MAX_ATTEMPTS);
                sleep(waitMs);
            } catch (HttpClientErrorException e) {
                lastError = e;
                /*
                 * Client errors (non-429) not retryable.
                 */
                throw AiProviderException.from(e);
            } catch (HttpServerErrorException e) {
                lastError = e;
                if (attempts >= MAX_ATTEMPTS) {
                    logger.warn("Groq 5xx error — max attempts ({}) reached", MAX_ATTEMPTS);
                    throw AiProviderException.from(e);
                }
                long delayMs = backoffWithJitter(attempts - 1);
                logger.warn("Groq 5xx error — retrying in {}ms (attempt {}/{})",
                        delayMs, attempts, MAX_ATTEMPTS);
                sleep(delayMs);
            } catch (ResourceAccessException e) {
                lastError = e;
                if (attempts >= MAX_ATTEMPTS) {
                    logger.warn("Groq connectivity error — max attempts ({}) reached", MAX_ATTEMPTS);
                    throw AiProviderException.from(e);
                }
                long delayMs = backoffWithJitter(attempts - 1);
                logger.warn("Groq connectivity error — retrying in {}ms (attempt {}/{})",
                        delayMs, attempts, MAX_ATTEMPTS);
                sleep(delayMs);
            } catch (Exception e) {
                lastError = e;
                if (attempts >= MAX_ATTEMPTS) {
                    logger.warn("Groq unexpected error — max attempts ({}) reached", MAX_ATTEMPTS);
                    throw AiProviderException.from(e);
                }
                long delayMs = backoffWithJitter(attempts - 1);
                logger.warn("Groq unexpected error — retrying in {}ms (attempt {}/{})",
                        delayMs, attempts, MAX_ATTEMPTS);
                sleep(delayMs);
            }
        }

        throw AiProviderException.from(lastError);
    }

    private long backoffWithJitter(int retryIndex) {
        return BASE_BACKOFF_MS * (long) Math.pow(2, retryIndex)
                + ThreadLocalRandom.current().nextLong(0, MAX_JITTER_MS);
    }

    private long parseRetryAfter(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return 30_000L;
        }
        try {
            return Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException e) {
            /*
             * HTTP-date not parsed; use default.
             */
            return 30_000L;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Groq retry interrupted", e);
        }
    }
}
