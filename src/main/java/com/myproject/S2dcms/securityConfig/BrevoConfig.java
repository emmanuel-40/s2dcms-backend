package com.myproject.S2dcms.securityConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sibApi.TransactionalEmailsApi;
import sendinblue.ApiClient;
import sendinblue.auth.ApiKeyAuth;

@Configuration
public class BrevoConfig {

    private static final Logger logger = LoggerFactory.getLogger(BrevoConfig.class);

    private static final String KEY_PREFIX = "xkeysib-";

    @Value("${brevo.api.key}")
    private String apiKey;

    @Bean
    public TransactionalEmailsApi transactionalEmailsApi() {

        ApiClient apiClient = new ApiClient();

        ApiKeyAuth apiKeyAuth =
                (ApiKeyAuth) apiClient.getAuthentication("api-key");

        // A key pasted into an environment variable often picks up a trailing newline or a
        // stray space, and Brevo answers that with an opaque
        // {"code":"unauthorized","message":"Key not found"} - indistinguishable from a revoked
        // key. Normalising here removes that whole class of failure.
        String normalisedKey = apiKey == null ? "" : apiKey.trim();

        // Never log the key itself. Length and shape are enough to tell "unset", "pasted with
        // whitespace" and "wrong key" apart, which is all that was ever needed.
        if (normalisedKey.isEmpty()) {
            logger.error("Brevo API key is NOT set (brevo.api.key / BREVO_API_KEY). "
                    + "Every email will fail with a 401 from Brevo.");
        } else if (!normalisedKey.startsWith(KEY_PREFIX)) {
            logger.error("Brevo API key does not start with '{}' (length {}). "
                            + "It is probably truncated or was pasted with extra characters.",
                    KEY_PREFIX, normalisedKey.length());
        } else {
            logger.info("Brevo API key loaded (length {})", normalisedKey.length());
        }

        apiKeyAuth.setApiKey(normalisedKey);

        return new TransactionalEmailsApi(apiClient);
    }
}
