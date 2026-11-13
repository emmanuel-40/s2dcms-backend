package com.myproject.S2dcms.repository;

import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepartmentRepo extends JpaRepository<Department, Long> {

    // Login by email (case-insensitive)
    Optional<Department> findByEmailIgnoreCase(String email);

    Optional<Department> findByPasswordResetToken(String token);

    Page<Department> findByRoleNot(Role role, Pageable pageable);
}
