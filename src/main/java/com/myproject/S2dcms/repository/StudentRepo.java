package com.myproject.S2dcms.repository;

import com.myproject.S2dcms.model.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StudentRepo extends JpaRepository<Student, Long> {

    /*
     * Use EntityGraph to fetch department eagerly and avoid N+1/lazy issues.
     */
    @Override
    @EntityGraph(attributePaths = {"department"})
    List<Student> findAll();

    @Override
    @EntityGraph(attributePaths = {"department"})
    Page<Student> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"department"})
    Optional<Student> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"department"})
    Optional<Student> findByVerificationToken(String token);

    @EntityGraph(attributePaths = {"department"})
    Optional<Student> findByPasswordResetToken(String token);
}
