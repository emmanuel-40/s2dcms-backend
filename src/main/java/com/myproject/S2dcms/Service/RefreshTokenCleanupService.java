package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // Runs every 5hrs
    @Scheduled(cron = "0 0 */5 * * *")
    public void cleanupOldTokens() {
        logger.debug("Refresh token cleanup running");
        refreshTokenRepository.deleteExpiredOrRevokedTokens();
        logger.debug("Refresh token cleanup completed");
    }
}
