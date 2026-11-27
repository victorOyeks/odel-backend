package com.odel.student.repository;

import com.odel.student.entity.StudentStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StudentStatusChangeRepository extends JpaRepository<StudentStatusChange, UUID> {
    List<StudentStatusChange> findByStudentIdOrderByCreatedAt(UUID studentId);
}
