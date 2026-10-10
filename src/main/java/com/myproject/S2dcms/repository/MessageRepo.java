package com.myproject.S2dcms.repository;

import com.myproject.S2dcms.model.Message;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.Message.Status;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MessageRepo extends JpaRepository<Message, Long> {

    // Student queries (paginated for safety)

    // Fetch all messages for a student, paginated
    // EntityGraph fetches student+department in the same query: without it the
    // LAZY ManyToOnes are touched per row inside MessagePreviewDto/MessageResponse,
    // turning 1 list query into 1 + 2N (classic N+1).
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByStudent(Student student, Pageable pageable);

    // Fetch messages by student and status, paginated
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByStudentAndStatus(Student student, Status status, Pageable pageable);

    // Department queries (paginated)

    // Fetch all messages for a department, paginated
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByDepartment(Department department, Pageable pageable);

    // Fetch messages by department and status, paginated
    @EntityGraph(attributePaths = {"student", "department"})
    Page<Message> findByDepartmentAndStatus(Department department, Status status, Pageable pageable);
}
