package com.myproject.S2dcms.repository;

import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.RefreshToken;
import com.myproject.S2dcms.model.Student;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.token = :token")
    @Transactional(readOnly = true)
    Optional<RefreshToken> findByTokenForUpdate(@Param("token") String token);

    List<RefreshToken> findByStudent(Student student);

    List<RefreshToken> findByDepartment(Department department);

    List<RefreshToken> findTop5ByStudentOrderByCreatedAtDesc(Student student);

    List<RefreshToken> findTop5ByDepartmentOrderByCreatedAtDesc(Department department);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.student = :student AND rt.id NOT IN :ids")
    void revokeAllExceptNewestForStudent(@Param("student") Student student, @Param("ids") List<Long> ids);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.department = :department AND rt.id NOT IN :ids")
    void revokeAllExceptNewestForDepartment(@Param("department") Department department, @Param("ids") List<Long> ids);

    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiryDate < CURRENT_TIMESTAMP OR rt.revoked = true")
    void deleteExpiredOrRevokedTokens();


    void deleteByStudent(Student student);

    void deleteByDepartment(Department department);
}
