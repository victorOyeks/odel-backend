package com.odel.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped: every query only sees the current university's users. */
public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("select u from User u where lower(u.email) = lower(?1)")
    Optional<User> findByEmail(String email);
}
