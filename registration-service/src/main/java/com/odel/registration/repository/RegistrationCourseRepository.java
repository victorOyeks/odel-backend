package com.odel.registration.repository;

import com.odel.registration.entity.RegistrationCourse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RegistrationCourseRepository extends JpaRepository<RegistrationCourse, UUID> {
    @Query("select c from RegistrationCourse c where c.registrationId = ?1")
    List<RegistrationCourse> forRegistration(UUID registrationId);

    @Query("select c from RegistrationCourse c where c.registrationId in ?1")
    List<RegistrationCourse> forRegistrations(Collection<UUID> registrationIds);

    @Query("select count(c) > 0 from RegistrationCourse c where c.carryOverId = ?1")
    boolean usesCarryOver(UUID carryOverId);
}
