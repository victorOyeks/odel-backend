package com.odel.student.repository;

import com.odel.student.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped: every query only sees the current university's students. */
public interface StudentRepository extends JpaRepository<Student, UUID>, JpaSpecificationExecutor<Student> {
    Optional<Student> findByMatricNumber(String matricNumber);

    Optional<Student> findByUserId(UUID userId);

    Optional<Student> findFirstByEmailIgnoreCase(String email);

    long countByStatus(Student.Status status);
}
