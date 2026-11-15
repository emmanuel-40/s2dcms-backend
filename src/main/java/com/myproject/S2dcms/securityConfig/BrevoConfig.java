package com.myproject.S2dcms.securityConfig;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

import sibApi.TransactionalEmailsApi;
import sendinblue.ApiClient;
import sendinblue.auth.ApiKeyAuth;

@Configuration
public class BrevoConfig {

    private static final Logger logger = LoggerFactory.getLogger(BrevoConfig.class);

    private static final String KEY_PREFIX = "xkeysib-";

    @Value("${brevo.api.key}")
    private String apiKey;

    @Value("${brevo.request.connect-timeout:10s}")
    private java.time.Duration brevoConnectTimeout;

    @Value("${brevo.request.read-timeout:10s}")
    private java.time.Duration brevoReadTimeout;

    @Bean
    public TransactionalEmailsApi transactionalEmailsApi() {

        ApiClient apiClient = new ApiClient();

        ApiKeyAuth apiKeyAuth =
                (ApiKeyAuth) apiClient.getAuthentication("api-key");

        /*
         * Normalize API key.
         */
        String normalisedKey = apiKey == null ? "" : apiKey.trim();

        /*
         * Log key shape only.
         */
        if (normalisedKey.isEmpty()) {
            logger.error("Brevo API key is NOT set (brevo.api.key / BREVO_API_KEY). "
                    + "Every email will fail with a 401 from Brevo.");
        } else if (!normalisedKey.startsWith(KEY_PREFIX)) {
           
        } else {
            logger.info("Brevo API key loaded (length {})", normalisedKey.length());
        }

        apiKeyAuth.setApiKey(normalisedKey);

        /*
         * Bound HTTP client timeouts.
         */
        OkHttpClient httpClient = apiClient.getHttpClient().newBuilder()
                .connectTimeout(brevoConnectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(brevoReadTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(brevoReadTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
        apiClient.setHttpClient(httpClient);

        return new TransactionalEmailsApi(apiClient);
    }
}
