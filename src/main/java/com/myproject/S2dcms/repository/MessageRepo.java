package com.myproject.S2dcms.repository;

import com.myproject.S2dcms.model.Message;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.Message.Status;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MessageRepo extends JpaRepository<Message, Long> {

    /*
     * Use EntityGraph to fetch relations eagerly.
     */
    @Override
    @EntityGraph(attributePaths = {"student", "department"})
    Optional<Message> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Message m WHERE m.id = :id")
    Optional<Message> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByStudent(Student student, Pageable pageable);

    // Fetch messages by student and status, paginated
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByStudentAndStatus(Student student, Status status, Pageable pageable);

    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByDepartment(Department department, Pageable pageable);

    // Fetch messages by department and status, paginated
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByDepartmentAndStatus(Department department, Status status, Pageable pageable);

    // Search by title/content for a student
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByStudentAndTitleContainingIgnoreCaseOrContentContainingIgnoreCase(Student student, String titleSearch, String contentSearch, Pageable pageable);

    // Search by title/content for a student filtered by status
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByStudentAndStatusAndTitleContainingIgnoreCaseOrContentContainingIgnoreCase(Student student, Status status, String titleSearch, String contentSearch, Pageable pageable);

    // Search by title/content for a department
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByDepartmentAndTitleContainingIgnoreCaseOrContentContainingIgnoreCase(Department department, String titleSearch, String contentSearch, Pageable pageable);

    // Search by title/content for a department filtered by status
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByDepartmentAndStatusAndTitleContainingIgnoreCaseOrContentContainingIgnoreCase(Department department, Status status, String titleSearch, String contentSearch, Pageable pageable);

    long countByDepartment(Department department);

    long countByDepartmentAndStatus(Department department, Status status);
}
