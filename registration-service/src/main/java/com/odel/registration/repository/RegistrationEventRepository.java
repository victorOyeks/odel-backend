package com.odel.registration.repository;

import com.odel.registration.entity.RegistrationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RegistrationEventRepository extends JpaRepository<RegistrationEvent, UUID> {
    @Query("select e from RegistrationEvent e where e.registrationId = ?1 order by e.createdAt")
    List<RegistrationEvent> forRegistration(UUID registrationId);
}
