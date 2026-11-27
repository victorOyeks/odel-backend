package com.odel.student.repository;

import com.odel.student.entity.StudentImport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StudentImportRepository extends JpaRepository<StudentImport, UUID> {
    Page<StudentImport> findByStatusOrderByImportedAtDesc(StudentImport.Status status, Pageable page);

    Optional<StudentImport> findFirstByStatusOrderByImportedAtDesc(StudentImport.Status status);
}
