package com.myproject.S2dcms.Exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * Raised whenever the Groq (OpenAI-compatible) provider cannot fulfil an AI request.
 *
 * <p>The exception carries the HTTP status the API should expose plus one message that is safe to
 * send to a browser. Everything the provider actually said — its identity, quota state, response
 * body and any authentication complaint — stays in the server log, because relaying it would leak
 * infrastructure detail (and, on a {@code 401}, the fact that our own key is the broken part) to
 * whoever happens to press the button.
 *
 * <p>Mapping applied by {@link #from(Throwable)}:
 * <ul>
 *   <li>provider {@code 429} to {@code 429} with {@code Retry-After} (upstream value, else 30s)</li>
 *   <li>provider {@code 401} / {@code 403} to {@code 503}: our key is at fault, so the client is
 *       told the dependency is down rather than being shown a credentials error</li>
 *   <li>provider {@code 408} and any {@code 5xx} to {@code 503} with {@code Retry-After}</li>
 *   <li>provider {@code 400}, {@code 404}, {@code 413}, {@code 422} to {@code 502}: our payload was rejected</li>
 *   <li>connect failures and socket timeouts to {@code 503} with {@code Retry-After}</li>
 *   <li>a usable {@code choices[0].message.content} missing from a 2xx body to {@code 502}</li>
 *   <li>anything unrecognised to {@code 500}</li>
 * </ul>
 */
public class AiProviderException extends RuntimeException {

    private static final Logger logger = LoggerFactory.getLogger(AiProviderException.class);

    /** Applied when the provider asks us to back off without saying for how long. */
    private static final long FALLBACK_RETRY_AFTER_SECONDS = 30L;

    /** Anything longer is a stalled/garbage header, not a wait a user should be asked to keep. */
    private static final long MAX_TRUSTED_RETRY_AFTER_SECONDS = 3_600L;

    private static final int MAX_LOGGED_BODY_CHARS = 200;

    private static final String RATE_LIMITED = "The AI service is busy right now. Please try again in %d seconds.";
    private static final String UNAVAILABLE = "The AI service is unavailable right now. Please try again shortly.";
    private static final String REJECTED = "The AI service could not process this text. Try shortening or rephrasing it.";
    private static final String UNEXPECTED = "The AI service returned an unusable reply. Please try again.";
    private static final String UNKNOWN = "AI generation failed. Please try again later.";

    private final HttpStatus status;
    private final Long retryAfterSeconds;

    private AiProviderException(String clientSafeMessage, HttpStatus status, Long retryAfterSeconds, Throwable cause) {
        super(clientSafeMessage, cause);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Translates any throwable raised around a provider call into a mapped, client-safe error.
     * An {@code AiProviderException} that was already classified is passed through untouched so the
     * most specific mapping survives the call stack.
     */
    public static AiProviderException from(Throwable cause) {
        if (cause instanceof AiProviderException alreadyClassified) {
            return alreadyClassified;
        }
        if (cause instanceof RestClientResponseException upstream) {
            return fromUpstreamResponse(upstream);
        }
        if (cause instanceof ResourceAccessException || cause instanceof IOException || isConnectivityFailure(cause)) {
            logger.warn("AI provider unreachable: {}", rootMessage(cause));
            return new AiProviderException(UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, FALLBACK_RETRY_AFTER_SECONDS, cause);
        }
        logger.error("Unexpected AI provider failure", cause);
        return new AiProviderException(UNKNOWN, HttpStatus.INTERNAL_SERVER_ERROR, null, cause);
    }

    /** The provider answered 2xx but the payload carries no usable assistant message. */
    public static AiProviderException unexpectedResponse() {
        logger.warn("AI provider returned a payload with no usable choices[0].message.content");
        return new AiProviderException(UNEXPECTED, HttpStatus.BAD_GATEWAY, null, null);
    }

    private static AiProviderException fromUpstreamResponse(RestClientResponseException upstream) {
        int code = upstream.getStatusCode().value();
        String detail = abbreviate(upstream.getResponseBodyAsString());

        if (code == 429) {
            long retryAfter = retryAfterOrDefault(upstream.getResponseHeaders());
            logger.warn("AI provider rate limited (429, retry after {}s, body={})", retryAfter, detail);
            return new AiProviderException(String.format(RATE_LIMITED, retryAfter),
                    HttpStatus.TOO_MANY_REQUESTS, retryAfter, upstream);
        }
        if (code == 401 || code == 403) {
            logger.error("AI provider rejected our own credentials ({} body={}) - verify the server-side API key", code, detail);
            return new AiProviderException(UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, FALLBACK_RETRY_AFTER_SECONDS, upstream);
        }
        if (code == 408 || code >= 500) {
            logger.warn("AI provider unavailable ({} body={})", code, detail);
            return new AiProviderException(UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, FALLBACK_RETRY_AFTER_SECONDS, upstream);
        }
        if (code >= 400) {
            logger.warn("AI provider rejected the request payload ({} body={})", code, detail);
            return new AiProviderException(REJECTED, HttpStatus.BAD_GATEWAY, null, upstream);
        }
        logger.warn("AI provider answered with an unexpected status ({})", code);
        return new AiProviderException(UNKNOWN, HttpStatus.INTERNAL_SERVER_ERROR, null, upstream);
    }

    /** Renders this error as the response body the AI endpoints return (plain text, like their successes). */
    public ResponseEntity<String> toResponseEntity() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        return builder.body(getMessage());
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Seconds to wait before retrying, or {@code null} when no {@code Retry-After} applies. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static long retryAfterOrDefault(HttpHeaders headers) {
        String raw = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw != null) {
            try {
                long seconds = Long.parseLong(raw.trim());
                if (seconds >= 0 && seconds <= MAX_TRUSTED_RETRY_AFTER_SECONDS) {
                    return seconds;
                }
            } catch (NumberFormatException ignored) {
                // HTTP-date form or garbage: fall back to the default window
            }
        }
        return FALLBACK_RETRY_AFTER_SECONDS;
    }

    private static boolean isConnectivityFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /** Keeps provider detail in the log without dumping a whole (possibly huge) body into it. */
    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String flattened = body.replaceAll("\\s+", " ").trim();
        return flattened.length() <= MAX_LOGGED_BODY_CHARS
                ? flattened
                : flattened.substring(0, MAX_LOGGED_BODY_CHARS) + "...";
    }
}
