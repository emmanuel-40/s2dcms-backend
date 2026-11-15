package com.myproject.S2dcms.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.S2dcms.dto.email.EmailMessage;
import com.myproject.S2dcms.securityConfig.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import sendinblue.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/* Consumes email messages from RabbitMQ with Redis deduplication.
 *
 * At-least-once delivery with Redis deduplication â€” not exactly-once to the
 * external provider (Redis cannot coordinate atomically with Brevo). If sending
 * succeeds but Redis state update fails, a redelivery may duplicate the email.
 *
 * Redis state machine keyed on email:dedup:<eventId>:
 *   PROCESSING - message being handled; other consumers get delayed requeue
 *   SENT       - email delivered; redeliveries skipped
 *   FAILED     - delivery permanently failed; redeliveries skipped until TTL
 *
 * All Redis transitions are atomic Lua scripts with per-attempt ownership tokens.
 * Only the claimant can transition state or renew the lease.
 */
@Service
public class EmailConsumerService {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumerService.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500L;
    private static final long MAX_JITTER_MS = 300L;

    private static final String STATE_PROCESSING = "PROCESSING";
    private static final String STATE_SENT = "SENT";
    private static final String STATE_FAILED = "FAILED";

    private static final Duration EMAIL_DEDUP_TTL = Duration.ofHours(1);
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(30);
    private static final Duration FAILED_TTL = Duration.ofMinutes(10);

    // Lua: claim a key atomically (SET IF ABSENT). Returns 1 if claimed, 0 if exists.
    private static final String CLAIM_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then " +
            "  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
            "  return 1 " +
            "end " +
            "return 0";

    // Lua: transition state only if owner matches. Returns 1 if transitioned, 0 if wrong owner, -1 if key missing.
    private static final String TRANSITION_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then return -1 end " +
            "local state = cjson.decode(existing) " +
            "if state.owner ~= ARGV[1] then return 0 end " +
            "state.state = ARGV[2] " +
            "state.completedAt = tonumber(ARGV[3]) " +
            "redis.call('SET', KEYS[1], cjson.encode(state), 'EX', ARGV[4]) " +
            "return 1";

    // Lua: reclaim a stale PROCESSING entry atomically. Returns 1 if reclaimed, 0 if not stale, 2 if same owner, -1 if missing.
    private static final String STALE_RECLAIM_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then return -1 end " +
            "local state = cjson.decode(existing) " +
            "if state.state ~= 'PROCESSING' then return 0 end " +
            "if state.owner == ARGV[1] then return 2 end " +
            "local createdAt = tonumber(state.createdAt or 0) " +
            "local now = tonumber(ARGV[2]) " +
            "local staleAfter = tonumber(ARGV[3]) " +
            "if now - createdAt > staleAfter then " +
            "  state.owner = ARGV[1] " +
            "  state.state = 'PROCESSING' " +
            "  state.createdAt = tonumber(ARGV[4]) " +
            "  redis.call('SET', KEYS[1], cjson.encode(state), 'EX', ARGV[5]) " +
            "  return 1 " +
            "end " +
            "return 0";

    // Lua: renew processing lease only if owner matches and state is PROCESSING.
    // Updates createdAt and extends TTL. Returns 1 if renewed, 0 if wrong owner/wrong state, -1 if missing.
    private static final String RENEW_LEASE_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then return -1 end " +
            "local state = cjson.decode(existing) " +
            "if state.owner ~= ARGV[1] then return 0 end " +
            "if state.state ~= 'PROCESSING' then return 0 end " +
            "state.createdAt = tonumber(ARGV[2]) " +
            "redis.call('SET', KEYS[1], cjson.encode(state), 'EX', ARGV[3]) " +
            "return 1";

    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> claimScript;
    private final DefaultRedisScript<Long> transitionScript;
    private final DefaultRedisScript<Long> staleReclaimScript;
    private final DefaultRedisScript<Long> renewLeaseScript;

    private final MailService mailService;
    private final StringRedisTemplate redisTemplate;

    public EmailConsumerService(MailService mailService, StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper) {
        this.mailService = mailService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.claimScript = new DefaultRedisScript<>(CLAIM_SCRIPT, Long.class);
        this.transitionScript = new DefaultRedisScript<>(TRANSITION_SCRIPT, Long.class);
        this.staleReclaimScript = new DefaultRedisScript<>(STALE_RECLAIM_SCRIPT, Long.class);
        this.renewLeaseScript = new DefaultRedisScript<>(RENEW_LEASE_SCRIPT, Long.class);
    }

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
    public void consumeEmailMessage(EmailMessage emailMessage) {
        String eventId = emailMessage.getEventId();
        if (eventId == null || eventId.isBlank()) {
            log.error("Received email message with null/blank eventId; rejecting message");
            throw new IllegalArgumentException("Email message missing eventId");
        }

        String dedupKey = "email:dedup:" + eventId;

        try {
            Map<String, Object> claimState = newClaimState();
            String claimJson = toJson(claimState);
            Long claimed = redisTemplate.execute(
                    claimScript, Collections.singletonList(dedupKey), claimJson, String.valueOf(PROCESSING_TTL.getSeconds()));

            if (claimed != null && claimed == 1L) {
                String ownershipToken = (String) claimState.get("owner");
                processAndComplete(emailMessage, dedupKey, ownershipToken);
                return;
            }

            // Key exists - read existing state
            String existingJson = (String) redisTemplate.opsForValue().get(dedupKey);
            if (existingJson == null) {
                // Key expired between claim and read - retry claim
                Map<String, Object> retryClaim = newClaimState();
                String retryJson = toJson(retryClaim);
                String ownershipToken = (String) retryClaim.get("owner");
                claimed = redisTemplate.execute(
                        claimScript, Collections.singletonList(dedupKey),
                        retryJson, String.valueOf(PROCESSING_TTL.getSeconds()));
                if (claimed != null && claimed == 1L) {
                    processAndComplete(emailMessage, dedupKey, ownershipToken);
                    return;
                }
                existingJson = (String) redisTemplate.opsForValue().get(dedupKey);
            }

            if (existingJson == null) {
                log.error("Unable to determine email dedup state for eventId {}, rejecting message", eventId);
                throw new RuntimeException("Idempotency store unavailable");
            }

            Map<String, Object> state = parseState(existingJson);
            if (state == null) {
                log.error("Corrupt dedup state for eventId {}, rejecting message", eventId);
                throw new RuntimeException("Idempotency store unavailable");
            }

            String stateValue = (String) state.get("state");
            String existingOwner = (String) state.get("owner");

            switch (stateValue) {
                case STATE_SENT:
                    log.info("Skipping duplicate email delivery - already sent (eventId: {})", eventId);
                    return; // Ack - skip duplicate

                case STATE_FAILED:
                    // FAILED is terminal within its TTL. After TTL expires, the key
                    // is eligible for reclamation by a new claim attempt.
                    log.info("Skipping previously failed email (eventId: {})", eventId);
                    return; // Ack - terminal failure within TTL

                case STATE_PROCESSING:
                    if (isStale(state)) {
                        // Try atomic stale reclaim
                        String newOwnerToken = UUID.randomUUID().toString();
                        Long reclaimed = redisTemplate.execute(
                                staleReclaimScript, Collections.singletonList(dedupKey),
                                newOwnerToken,
                                String.valueOf(Instant.now().getEpochSecond()),
                                String.valueOf(PROCESSING_TTL.getSeconds()),
                                String.valueOf(Instant.now().getEpochSecond()),
                                String.valueOf(PROCESSING_TTL.getSeconds()));
                        if (reclaimed != null && reclaimed == 1L) {
                            processAndComplete(emailMessage, dedupKey, newOwnerToken);
                            return;
                        }
                        if (reclaimed != null && reclaimed == 2L) {
                            // Same owner - proceed with existing token
                            processAndComplete(emailMessage, dedupKey, existingOwner);
                            return;
                        }
                        // Another consumer actively processing - do NOT silently ack.
                        // Reject the message so RabbitMQ requeues it for a delayed retry.
                        log.warn("Email being actively processed by another consumer (eventId: {}) - requeueing", eventId);
                        throw new RuntimeException("Email being processed by another consumer");
                    }
                    // Another consumer actively processing - requeue for delayed retry
                    log.warn("Email being actively processed by another instance (eventId: {}) - requeueing", eventId);
                    throw new RuntimeException("Email being processed by another consumer");
            }

        } catch (Exception e) {
            // Redis failure or active processing: reject to trigger RabbitMQ requeue/DLQ
            log.error("Redis failure or active processing for eventId {}, rejecting message", eventId, e);
            throw new RuntimeException("Email processing blocked", e);
        }
    }

    private void processAndComplete(EmailMessage emailMessage, String dedupKey, String ownershipToken) {
        String eventId = emailMessage.getEventId();
        int attempts = 0;
        Exception lastError = null;

        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            try {
                log.info("Processing email for: {} with type: {} (eventId: {}, attempt {}/{})",
                        emailMessage.getTo(), emailMessage.getType(), eventId, attempts, MAX_ATTEMPTS);

                switch (emailMessage.getType()) {
                    case "VERIFICATION":
                        mailService.sendVerificationEmail(emailMessage.getTo(), emailMessage.getToken());
                        log.info("Verification email sent to: {} (eventId: {})", emailMessage.getTo(), eventId);
                        markAsSent(dedupKey, ownershipToken);
                        return;
                    case "PASSWORD_RESET_STUDENT":
                        mailService.sendPasswordResetEmail(emailMessage.getTo(), emailMessage.getToken());
                        log.info("Password reset email sent to student: {} (eventId: {})", emailMessage.getTo(), eventId);
                        markAsSent(dedupKey, ownershipToken);
                        return;
                    case "PASSWORD_RESET_DEPARTMENT":
                        mailService.sendDepartmentPasswordResetEmail(emailMessage.getTo(), emailMessage.getToken());
                        log.info("Password reset email sent to department: {} (eventId: {})", emailMessage.getTo(), eventId);
                        markAsSent(dedupKey, ownershipToken);
                        return;
                    case "CONTACT":
                        mailService.sendContactEmail(emailMessage.getTo(), emailMessage.getName(),
                                emailMessage.getSenderEmail(), emailMessage.getMessage());
                        log.info("Contact email sent to: {} (eventId: {})", emailMessage.getTo(), eventId);
                        markAsSent(dedupKey, ownershipToken);
                        return;
                    case "COMPLAINT_REPLY":
                        mailService.sendComplaintReplyEmail(emailMessage.getTo(), emailMessage.getName(),
                                emailMessage.getMessage());
                        log.info("Complaint reply email sent to: {} (eventId: {})", emailMessage.getTo(), eventId);
                        markAsSent(dedupKey, ownershipToken);
                        return;
                    default:
                        // Unknown email type - reject immediately, do not retry
                        log.error("Unknown email type: {} (eventId: {}) - rejecting", emailMessage.getType(), eventId);
                        markAsFailed(dedupKey, ownershipToken);
                        throw new IllegalArgumentException("Unknown email type: " + emailMessage.getType());
                }
            } catch (ApiException e) {
                lastError = e;
                int code = e.getCode();
                boolean isRetryable = (code >= 500) || code == 429;

                if (!isRetryable || attempts >= MAX_ATTEMPTS) {
                    log.error("Failed to send email to {} (eventId: {}, Brevo status {}): {}",
                            emailMessage.getTo(), eventId, code, e.getResponseBody(), e);
                    markAsFailed(dedupKey, ownershipToken);
                    throw new RuntimeException("Email delivery failed after " + MAX_ATTEMPTS + " attempts", e);
                }

                long delayMs = backoffWithJitter(attempts - 1);
                log.warn("Brevo transient error {} for {} (eventId: {}) â€” retrying in {}ms (attempt {}/{})",
                        code, emailMessage.getTo(), eventId, delayMs, attempts, MAX_ATTEMPTS);
                renewProcessingLease(dedupKey, ownershipToken);
                sleep(delayMs);
            } catch (IllegalArgumentException e) {
                // Unknown type or other client error - already marked FAILED, don't retry
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (attempts >= MAX_ATTEMPTS) {
                    log.error("Error processing email message after {} attempts (eventId: {}): {}",
                            MAX_ATTEMPTS, eventId, e.getMessage(), e);
                    markAsFailed(dedupKey, ownershipToken);
                    throw new RuntimeException("Email processing failed after " + MAX_ATTEMPTS + " attempts", e);
                }

                long delayMs = backoffWithJitter(attempts - 1);
                log.warn("Error processing email for {} (eventId: {}) â€” retrying in {}ms (attempt {}/{})",
                        emailMessage.getTo(), eventId, delayMs, attempts, MAX_ATTEMPTS, e.getMessage());
                renewProcessingLease(dedupKey, ownershipToken);
                sleep(delayMs);
            }
        }

        markAsFailed(dedupKey, ownershipToken);
        throw new RuntimeException("Email delivery failed after " + MAX_ATTEMPTS + " attempts", lastError);
    }

    private void renewProcessingLease(String dedupKey, String ownershipToken) {
        try {
            Long result = redisTemplate.execute(
                    renewLeaseScript, Collections.singletonList(dedupKey),
                    ownershipToken, String.valueOf(Instant.now().getEpochSecond()), String.valueOf(PROCESSING_TTL.getSeconds()));
            if (result != null && result == 0) {
                log.warn("Could not renew lease - ownership lost or state changed for key {}", dedupKey);
            }
        } catch (Exception e) {
            log.warn("Failed to renew processing lease for key {}", dedupKey, e);
        }
    }

    private void markAsSent(String dedupKey, String ownershipToken) {
        try {
            Long result = redisTemplate.execute(
                    transitionScript, Collections.singletonList(dedupKey),
                    ownershipToken, STATE_SENT, String.valueOf(Instant.now().getEpochSecond()),
                    String.valueOf(EMAIL_DEDUP_TTL.getSeconds()));
            if (result != null && result == 0) {
                // Ownership lost or state changed â€” email may have been sent but
                // we cannot mark it. A redelivery could result in a duplicate.
                log.warn("Could not mark email as SENT - ownership lost for key {}; possible duplicate delivery", dedupKey);
            }
        } catch (Exception e) {
            // Email was sent but Redis update failed â€” possible duplicate on redelivery.
            // This is the inherent limitation of at-least-once with Redis dedup.
            log.warn("Failed to mark email as SENT for key {}; possible duplicate delivery", dedupKey, e);
        }
    }

    private void markAsFailed(String dedupKey, String ownershipToken) {
        try {
            Long result = redisTemplate.execute(
                    transitionScript, Collections.singletonList(dedupKey),
                    ownershipToken, STATE_FAILED, String.valueOf(Instant.now().getEpochSecond()),
                    String.valueOf(FAILED_TTL.getSeconds()));
            if (result != null && result == 0) {
                log.warn("Could not mark email as FAILED - ownership lost for key {}", dedupKey);
            }
        } catch (Exception e) {
            log.warn("Failed to mark email as FAILED for key {}", dedupKey, e);
        }
    }

    private Map<String, Object> newClaimState() {
        Map<String, Object> state = new HashMap<>();
        state.put("state", STATE_PROCESSING);
        state.put("owner", UUID.randomUUID().toString());
        state.put("createdAt", Instant.now().getEpochSecond());
        return state;
    }

    private boolean isStale(Map<String, Object> state) {
        Object createdAt = state.get("createdAt");
        if (createdAt == null) {
            return true;
        }
        try {
            long epochSeconds;
            if (createdAt instanceof Number) {
                epochSeconds = ((Number) createdAt).longValue();
            } else {
                epochSeconds = Instant.parse(createdAt.toString()).getEpochSecond();
            }
            return Instant.now().getEpochSecond() - epochSeconds > PROCESSING_TTL.getSeconds();
        } catch (Exception e) {
            return true;
        }
    }

    private String toJson(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            // Serialization failure must throw â€” never return fake state
            throw new RuntimeException("Failed to serialize email dedup state", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize email dedup state", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseState(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize email dedup state", e);
            return null;
        }
    }

    private long backoffWithJitter(int retryIndex) {
        return BASE_BACKOFF_MS * (long) Math.pow(2, retryIndex)
                + ThreadLocalRandom.current().nextLong(0, MAX_JITTER_MS);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Email retry interrupted", e);
        }
    }
}
