package com.odel.identity.repository;

import com.odel.identity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Users looked up by id inside the current university only. (A query applies Hibernate's @TenantId filter;
 * the inherited findById does not.)
 */
public interface StudentUserRepository extends JpaRepository<User, UUID> {
    @Query("select u from User u where u.id = ?1")
    Optional<User> findInTenant(UUID id);
}
