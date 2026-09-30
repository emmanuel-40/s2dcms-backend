package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.RateLimitException;
import com.myproject.S2dcms.model.UserActionLimit;
import com.myproject.S2dcms.repository.UserActionLimitRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserActionService {

    private final UserActionLimitRepository limitRepository;
    @Value("${limit.max_attempt}")
    private int MAX_ATTEMPTS; // e.g., max 3 per hour
    @Value("${limit.cooldown_minutes}")
    private int COOLDOWN_MINUTES;     // reset after 60 min

    public UserActionService(UserActionLimitRepository limitRepository) {
        this.limitRepository = limitRepository;
    }

    public void checkRateLimit(String email, String action) {

        UserActionLimit limit = limitRepository
                .findByEmailAndAction(email, action)
                .orElse(new UserActionLimit());

        LocalDateTime now = LocalDateTime.now();

        if (limit.getId() == null) {
            limit.setEmail(email);
            limit.setAction(action);
            limit.setCount(1);
            limit.setLastRequest(now);
            limitRepository.save(limit);
            return;
        }

        //  FIRST check cooldown reset AFTER blocking logic
        if (limit.getCount() > MAX_ATTEMPTS) {
            if (limit.getLastRequest().plusMinutes(COOLDOWN_MINUTES).isAfter(now)) {
                long minutesRemaining = java.time.Duration.between(now, limit.getLastRequest().plusMinutes(COOLDOWN_MINUTES)).toMinutes();
                // More user-friendly error message for better UX
                if (minutesRemaining < 1) {
                    throw new RateLimitException("Too many attempts. Please try again in 1 minute.");
                } else if (minutesRemaining == 1) {
                    throw new RateLimitException("Too many attempts. Please try again in 1 minute.");
                } else {
                    throw new RateLimitException("Too many attempts. Please try again in " + minutesRemaining + " minutes.");
                }
            } else {
                // cooldown passed → reset
                limit.setCount(1);
                limit.setLastRequest(now);
                limitRepository.save(limit);
                return;
            }
        }

        // Increment count
        limit.setCount(limit.getCount() + 1);
        limit.setLastRequest(now);
        limitRepository.save(limit);
    }

    public int getRemainingAttempts(String email, String action) {
        UserActionLimit limit = limitRepository
                .findByEmailAndAction(email, action)
                .orElse(null);

        if (limit == null || limit.getId() == null) {
            return MAX_ATTEMPTS;
        }

        if (limit.getCount() > MAX_ATTEMPTS) {
            return 0;
        }

        return MAX_ATTEMPTS - limit.getCount() + 1;
    }

    public void resetRateLimit(String email, String action) {
        limitRepository.findByEmailAndAction(email, action).ifPresent(limit -> {
            limitRepository.delete(limit);
        });
    }
}