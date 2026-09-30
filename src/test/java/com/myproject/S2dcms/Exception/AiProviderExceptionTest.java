package com.myproject.S2dcms.Exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the two guarantees the AI endpoints make: the status a client may act on, and that no
 * provider detail travels further than the log.
 */
class AiProviderExceptionTest {

    private static RestClientResponseException upstream(HttpStatus status, String retryAfter, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return new RestClientResponseException("4xx/5xx from provider", status, status.name(),
                headers, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("provider 429 becomes 429 and keeps the upstream Retry-After window")
    void rateLimitedKeepsUpstreamRetryAfter() {
        AiProviderException mapped = AiProviderException.from(upstream(HttpStatus.TOO_MANY_REQUESTS, "12", "{\"error\":{}}"));

        assertThat(mapped.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(mapped.getRetryAfterSeconds()).isEqualTo(12L);
        assertThat(mapped.toResponseEntity().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
    }

    @Test
    @DisplayName("provider 429 without Retry-After falls back to 30s; garbage or absurd windows are ignored")
    void rateLimitedFallsBackToDefaultWindow() {
        assertThat(AiProviderException.from(upstream(HttpStatus.TOO_MANY_REQUESTS, null, "{}")).getRetryAfterSeconds())
                .isEqualTo(30L);
        assertThat(AiProviderException.from(upstream(HttpStatus.TOO_MANY_REQUESTS, "not-a-number", "{}")).getRetryAfterSeconds())
                .isEqualTo(30L);
        assertThat(AiProviderException.from(upstream(HttpStatus.TOO_MANY_REQUESTS, "86400", "{}")).getRetryAfterSeconds())
                .isEqualTo(30L);
    }

    @Test
    @DisplayName("a broken key on our side surfaces as 503, never as a 401 the client could learn from")
    void ourOwnAuthFailureIsHidden() {
        AiProviderException mapped = AiProviderException.from(
                upstream(HttpStatus.UNAUTHORIZED, null, "{\"error\":{\"message\":\"Invalid API key\"}}"));

        assertThat(mapped.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mapped.getMessage()).doesNotContainIgnoringCase("key", "groq", "invalid", "unauthorized");
        assertThat(mapped.toResponseEntity().getBody()).doesNotContainIgnoringCase("key", "groq");
    }

    @Test
    @DisplayName("provider outages are 503 with a Retry-After; our bad payloads are 502")
    void outagesAndRejectedPayloadsAreSeparated() {
        assertThat(AiProviderException.from(upstream(HttpStatus.BAD_GATEWAY, null, "boom")).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(AiProviderException.from(upstream(HttpStatus.REQUEST_TIMEOUT, null, "slow")).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(AiProviderException.from(upstream(HttpStatus.BAD_REQUEST, null, "too long")).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(AiProviderException.from(upstream(HttpStatus.UNPROCESSABLE_ENTITY, null, "bad")).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("connectivity failures become 503 and carry a Retry-After the SPA can honour")
    void connectivityFailuresAreRetryable() {
        AiProviderException refused = AiProviderException.from(new ResourceAccessException("connection refused"));
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.toResponseEntity().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");

        assertThat(AiProviderException.from(new RuntimeException(new SocketTimeoutException("read timed out"))).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("an unusable 2xx payload is a 502 with no provider identity in the body")
    void malformedPayloadIsBadGateway() {
        AiProviderException mapped = AiProviderException.unexpectedResponse();

        ResponseEntity<String> response = mapped.toResponseEntity();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).doesNotContainIgnoringCase("groq", "choices", "openai");
    }

    @Test
    @DisplayName("already-classified errors pass through so the specific mapping survives the call stack")
    void classificationSurvivesReThrowing() {
        AiProviderException original = AiProviderException.from(upstream(HttpStatus.TOO_MANY_REQUESTS, "7", "{}"));

        assertThat(AiProviderException.from(original)).isSameAs(original);
    }

    @Test
    @DisplayName("anything unrecognised is a 500 with a generic message and no Retry-After")
    void unknownFailuresStayGeneric() {
        AiProviderException mapped = AiProviderException.from(new IllegalStateException("boom"));

        assertThat(mapped.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(mapped.getRetryAfterSeconds()).isNull();
        assertThat(mapped.toResponseEntity().getBody()).isEqualTo("AI generation failed. Please try again later.");
    }
}