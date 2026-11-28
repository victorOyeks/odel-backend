package com.odel.registration.repository;

import com.odel.registration.entity.CarryOver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CarryOverRepository extends JpaRepository<CarryOver, UUID> {
    @Query("select c from CarryOver c where c.id = ?1")
    Optional<CarryOver> findInTenant(UUID id);

    @Query("select c from CarryOver c where c.studentId = ?1 order by c.createdAt desc")
    List<CarryOver> forStudent(UUID studentId);

    @Query("select c from CarryOver c where c.studentId = ?1 and c.status = 'OPEN' order by c.courseCode")
    List<CarryOver> openForStudent(UUID studentId);

    @Query("select c from CarryOver c where c.studentId = ?1 and c.courseId = ?2 and c.status = 'OPEN'")
    Optional<CarryOver> open(UUID studentId, UUID courseId);
}
