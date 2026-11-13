package com.myproject.S2dcms.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.S2dcms.dto.email.EmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for email dedup ownership + fail-closed Redis behaviour.
 * Lua scripts themselves are not executed here (mocked return values).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailConsumerServiceTest {

    @Mock
    private MailService mailService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EmailConsumerService consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        consumer = new EmailConsumerService(mailService, redisTemplate, objectMapper);
        // Field injection used by the service in production; tests construct via ctor
        // but @Autowired fields may be null — set via reflection if needed.
        try {
            var mailField = EmailConsumerService.class.getDeclaredField("mailService");
            mailField.setAccessible(true);
            mailField.set(consumer, mailService);
            var redisField = EmailConsumerService.class.getDeclaredField("redisTemplate");
            redisField.setAccessible(true);
            redisField.set(consumer, redisTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EmailMessage verificationMessage() {
        EmailMessage msg = new EmailMessage();
        msg.setEventId("evt-1");
        msg.setType("VERIFICATION");
        msg.setTo("student@test.com");
        msg.setToken("verify-token");
        return msg;
    }

    @Test
    void claimSuccess_usesSameOwnerTokenWrittenToRedis() throws Exception {
        ArgumentCaptor<Object> claimJsonCaptor = ArgumentCaptor.forClass(Object.class);

        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("email:dedup:evt-1")),
                claimJsonCaptor.capture(),
                any()))
                .thenReturn(1L);

        // markAsSent transition also calls execute
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), any(), any(), any()))
                .thenReturn(1L);

        consumer.consumeEmailMessage(verificationMessage());

        verify(mailService).sendVerificationEmail("student@test.com", "verify-token");

        @SuppressWarnings("unchecked")
        Map<String, Object> written = objectMapper.readValue(
                claimJsonCaptor.getValue().toString(), Map.class);
        String ownerWritten = (String) written.get("owner");
        assertNotNull(ownerWritten);
        assertTrue(written.get("createdAt") instanceof Number,
                "createdAt must be epoch seconds for Lua tonumber()");

        // markAsSent must receive the SAME owner that was claimed
        verify(redisTemplate, atLeastOnce()).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("email:dedup:evt-1")),
                eq(ownerWritten),
                eq("SENT"),
                anyString(),
                anyString());
    }

    @Test
    void redisFailure_rejectsMessageInsteadOfBypassingDedup() {
        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenThrow(new RuntimeException("Redis down"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> consumer.consumeEmailMessage(verificationMessage()));

        assertTrue(ex.getMessage().contains("Idempotency store unavailable")
                || (ex.getCause() != null && ex.getCause().getMessage().contains("Redis")));
        verifyNoInteractions(mailService);
    }

    @Test
    void alreadySent_skipsDelivery() throws Exception {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(0L);

        Map<String, Object> sent = new HashMap<>();
        sent.put("state", "SENT");
        sent.put("owner", "owner-1");
        sent.put("createdAt", System.currentTimeMillis() / 1000);
        when(valueOperations.get("email:dedup:evt-1"))
                .thenReturn(objectMapper.writeValueAsString(sent));

        consumer.consumeEmailMessage(verificationMessage());

        verifyNoInteractions(mailService);
    }

    @Test
    void staleReclaim_usesSameTokenForProcessing() throws Exception {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(0L);

        Map<String, Object> processing = new HashMap<>();
        processing.put("state", "PROCESSING");
        processing.put("owner", "old-owner");
        processing.put("createdAt", 0); // epoch 0 → stale
        when(valueOperations.get("email:dedup:evt-1"))
                .thenReturn(objectMapper.writeValueAsString(processing));

        ArgumentCaptor<Object> ownerCaptor = ArgumentCaptor.forClass(Object.class);
        // First execute after claim: stale reclaim → 1
        // Then markAsSent
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                ownerCaptor.capture(),
                any(), any(), any(), any()))
                .thenReturn(1L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), any(), any(), any()))
                .thenReturn(1L);

        consumer.consumeEmailMessage(verificationMessage());

        verify(mailService).sendVerificationEmail("student@test.com", "verify-token");
    }
}
