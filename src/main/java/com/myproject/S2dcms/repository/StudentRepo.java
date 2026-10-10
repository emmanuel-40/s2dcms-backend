package com.myproject.S2dcms.repository;

import com.myproject.S2dcms.model.Student;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StudentRepo extends JpaRepository<Student, Long> {

    // Student.department is FetchType.LAZY (Student.java:53), but EVERY read path dereferences a
    // real property on it: StudentResponse:19 calls student.getDepartment().getDepartmentName().
    // That DTO is built in StudentController /admin/all, StudentService.getProfile and
    // StudentService.updateProfile - none of which is @Transactional (only 3 @Transactional
    // annotations exist in the whole codebase, none on these paths).
   
    // @EntityGraph folds the join into the SAME query, which fixes both problems at once:
    //   1. the entity is fully populated before it goes detached (no exception), and
    //   2. the classic 1 + N select on /api/students/admin/all disappears (1 query, not 1 + N).
    //
    // Applied to every Student-returning method as a deliberate invariant: "a Student that leaves
    // this repository is always complete", so no future caller has to remember this.
    @Override
    @EntityGraph(attributePaths = {"department"})
    List<Student> findAll();

    @EntityGraph(attributePaths = {"department"})
    Optional<Student> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"department"})
    Optional<Student> findByVerificationToken(String token);

    @EntityGraph(attributePaths = {"department"})
    Optional<Student> findByPasswordResetToken(String token);
}
