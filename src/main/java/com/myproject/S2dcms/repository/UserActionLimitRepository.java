package com.myproject.S2dcms.repository;


import com.myproject.S2dcms.model.UserActionLimit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserActionLimitRepository extends JpaRepository<UserActionLimit, Long> {
    Optional<UserActionLimit> findByEmailAndAction(String email, String action);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ual FROM UserActionLimit ual WHERE ual.email = :email AND ual.action = :action")
    @Transactional(readOnly = true)
    Optional<UserActionLimit> findByEmailAndActionForUpdate(@Param("email") String email, @Param("action") String action);

    /*
     * Atomically increment count only if under the limit. Returns 1 if the increment
     * succeeded, 0 if the row doesn't exist yet or count is already at maxAttempts.
     * This is a single atomic UPDATE — no read-modify-write race, no row lock held
     * across the request, so concurrent logins don't serialize.
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserActionLimit ual SET ual.count = ual.count + 1, ual.lastRequest = :now WHERE ual.email = :email AND ual.action = :action AND ual.count < :maxAttempts")
    int incrementIfUnderLimit(@Param("email") String email, @Param("action") String action, @Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts);
}
