package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.RateLimitException;
import com.myproject.S2dcms.model.UserActionLimit;
import com.myproject.S2dcms.repository.UserActionLimitRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UserActionService {

    private final UserActionLimitRepository limitRepository;
    @Value("${limit.max_attempt}")
    private int MAX_ATTEMPTS;
    @Value("${limit.cooldown_minutes}")
    private int COOLDOWN_MINUTES;

    public UserActionService(UserActionLimitRepository limitRepository) {
        this.limitRepository = limitRepository;
    }

    /*
     * REQUIRES_NEW: this attempt counter MUST outlive the caller's transaction. AuthService.login
     * is @Transactional and throws InvalidPasswordException on a wrong password; with the default
     * REQUIRED propagation this save would join login's transaction and be erased by that
     * rollback - which would silently disable login rate limiting while looking like it works.
     * Suspending the caller and committing independently is what keeps the count durable.
     *
     * Concurrency model: atomic SQL increment (UPDATE ... WHERE count < maxAttempts) instead of
     * pessimistic row locking. Concurrent logins proceed in parallel — no SELECT FOR UPDATE
     * serialization. The DB UNIQUE(email, action) constraint guarantees at most one row per
     * (email, action), and DataIntegrityViolationException on INSERT is caught and re-read.
     *
     * Atomicity: incrementIfUnderLimit is a single UPDATE statement. The DB guarantees that
     * only one transaction can increment past maxAttempts - 1; the rest get 0 rows affected
     * and fall through to the cooldown check below.
     */
    @Transactional
    public void checkRateLimit(String email, String action) {

        LocalDateTime now = LocalDateTime.now();

        /*
         * Try atomic increment — succeeds only if count < maxAttempts.
         * No row lock held, so concurrent logins don't queue up.
         */
        int rows = limitRepository.incrementIfUnderLimit(email, action, now, MAX_ATTEMPTS);
        if (rows == 1) {
            return; // increment succeeded, login allowed
        }

        /*
         * rows == 0 means either:
         *   (a) no row exists yet (first-ever login for this email/action), or
         *   (b) count >= maxAttempts (rate limited or cooldown needed)
         *
         * Try INSERT — if a concurrent request already inserted, UNIQUE constraint
         * rejects us and we re-read the existing row.
         */
        UserActionLimit limit = new UserActionLimit();
        limit.setEmail(email);
        limit.setAction(action);
        limit.setCount(1);
        limit.setLastRequest(now);
        try {
            limitRepository.save(limit);
            return; // INSERT succeeded, first login for this email/action
        } catch (DataIntegrityViolationException e) {
            // Concurrent request inserted first — re-read the existing row
            limit = limitRepository.findByEmailAndAction(email, action)
                    .orElseThrow(() -> new RateLimitException("Rate limit error"));
        }

        /*
         * We have an existing row with count >= maxAttempts (otherwise the atomic
         * increment above would have succeeded). Check cooldown.
         */
        if (limit.getLastRequest().plusMinutes(COOLDOWN_MINUTES).isAfter(now)) {
            long minutesRemaining = java.time.Duration.between(now, limit.getLastRequest().plusMinutes(COOLDOWN_MINUTES)).toMinutes();
            if (minutesRemaining < 1) {
                throw new RateLimitException("Too many attempts. Please try again in 1 minute.");
            } else if (minutesRemaining == 1) {
                throw new RateLimitException("Too many attempts. Please try again in 1 minute.");
            } else {
                throw new RateLimitException("Too many attempts. Please try again in " + minutesRemaining + " minutes.");
            }
        }

        /*
         * Cooldown expired — reset count to 1 and allow login.
         * Use atomic increment again to avoid lost updates from concurrent resets.
         */
        int resetRows = limitRepository.incrementIfUnderLimit(email, action, now, 1);
        if (resetRows == 0) {
            // Another concurrent request reset first — re-read and increment
            limit = limitRepository.findByEmailAndAction(email, action)
                    .orElseThrow(() -> new RateLimitException("Rate limit error"));
            limit.setCount(limit.getCount() + 1);
            limit.setLastRequest(now);
            limitRepository.save(limit);
        }
    }

    public int getRemainingAttempts(String email, String action) {
        UserActionLimit limit = limitRepository
                .findByEmailAndAction(email, action)
                .orElse(null);

        if (limit == null || limit.getId() == null) {
            return MAX_ATTEMPTS;
        }

        if (limit.getCount() >= MAX_ATTEMPTS) {
            return 0;
        }

        return MAX_ATTEMPTS - limit.getCount() + 1;
    }

    /*
     * REQUIRES_NEW for the same reason as checkRateLimit: a successful login (or a verified
     * email) must clear the counter even though the surrounding transaction may roll back later.
     */
    @Transactional
    public void resetRateLimit(String email, String action) {
        limitRepository.findByEmailAndAction(email, action).ifPresent(limit -> {
            limitRepository.delete(limit);
        });
    }
}
