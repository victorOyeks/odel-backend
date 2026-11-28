package com.odel.registration.repository;

import com.odel.registration.entity.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistrationRepository extends JpaRepository<Registration, UUID> {
    @Query("select r from Registration r where r.id = ?1")
    Optional<Registration> findInTenant(UUID id);

    @Query("select r from Registration r where r.studentId = ?1 and r.semesterId = ?2")
    Optional<Registration> forStudent(UUID studentId, UUID semesterId);

    @Query("select r from Registration r where r.studentId = ?1 order by r.submittedAt desc")
    List<Registration> allForStudent(UUID studentId);

    @Query("select r from Registration r where r.semesterId = ?1")
    List<Registration> forSemester(UUID semesterId);

    @Query("select r from Registration r where r.verificationCode = ?1")
    Optional<Registration> byCode(String code);

    @Query("select count(r) > 0 from Registration r where r.slipNumber = ?1")
    boolean slipTaken(String slipNumber);
}
