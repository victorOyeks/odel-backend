package com.odel.identity.repository;

import com.odel.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Staff accounts (Google sign-in) of the current university; students' accounts are not staff. */
public interface StaffRepository extends JpaRepository<User, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from User x where x.id = ?1")
    Optional<User> findById(UUID id);

    @Query("select u from User u where u.authType = com.odel.identity.entity.User.AuthType.GOOGLE order by lower(u.fullName)")
    List<User> staff();
}
