package com.myproject.S2dcms.repository;


import com.myproject.S2dcms.model.UserActionLimit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserActionLimitRepository extends JpaRepository<UserActionLimit, Long> {
    Optional<UserActionLimit> findByEmailAndAction(String email, String action);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ual FROM UserActionLimit ual WHERE ual.email = :email AND ual.action = :action")
    Optional<UserActionLimit> findByEmailAndActionForUpdate(@Param("email") String email, @Param("action") String action);
}