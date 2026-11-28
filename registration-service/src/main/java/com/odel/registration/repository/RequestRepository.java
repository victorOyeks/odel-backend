package com.odel.registration.repository;

import com.odel.registration.entity.RegistrationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestRepository extends JpaRepository<RegistrationRequest, UUID> {
    @Query("select r from RegistrationRequest r where r.id = ?1")
    Optional<RegistrationRequest> findInTenant(UUID id);

    @Query("select r from RegistrationRequest r where r.studentId = ?1 and r.semesterId = ?2 order by r.createdAt desc")
    List<RegistrationRequest> forStudent(UUID studentId, UUID semesterId);

    @Query("select r from RegistrationRequest r where r.studentId = ?1 order by r.createdAt desc")
    List<RegistrationRequest> allForStudent(UUID studentId);

    @Query("select r from RegistrationRequest r where r.semesterId = ?1 order by r.createdAt desc")
    List<RegistrationRequest> forSemester(UUID semesterId);

    @Query("select r from RegistrationRequest r order by r.createdAt desc")
    List<RegistrationRequest> newestFirst();

    @Query("select r from RegistrationRequest r where r.studentId = ?1 and r.status = 'PENDING'")
    List<RegistrationRequest> pendingForStudent(UUID studentId);
}
