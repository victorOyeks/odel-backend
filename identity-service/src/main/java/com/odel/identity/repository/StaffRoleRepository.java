package com.odel.identity.repository;

import com.odel.identity.entity.RoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Role assignments of the current university. */
public interface StaffRoleRepository extends JpaRepository<RoleAssignment, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from RoleAssignment x where x.id = ?1")
    Optional<RoleAssignment> findById(UUID id);

    @Query("select r from RoleAssignment r where r.userId in ?1 order by r.createdAt")
    List<RoleAssignment> forUsers(Collection<UUID> userIds);

    @Modifying
    @Query("delete from RoleAssignment r where r.id in ?1")
    void deleteIds(Collection<UUID> ids);
}
