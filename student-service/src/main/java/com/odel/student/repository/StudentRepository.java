package com.odel.student.repository;

import com.odel.student.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped: every query only sees the current university's students. */
public interface StudentRepository extends JpaRepository<Student, UUID>, JpaSpecificationExecutor<Student> {
    /** By id inside the current university (a query applies the @TenantId filter; the inherited findById does not). */
    @Query("select s from Student s where s.id = ?1")
    Optional<Student> findInTenant(UUID id);

    @Query("select s from Student s where s.id in ?1")
    List<Student> findAllInTenant(Collection<UUID> ids);

    @Query("select s.centreId, count(s) from Student s where s.centreId is not null group by s.centreId")
    List<Object[]> countByCentre();
    Optional<Student> findByMatricNumber(String matricNumber);

    Optional<Student> findByUserId(UUID userId);

    Optional<Student> findFirstByEmailIgnoreCase(String email);

    long countByStatus(Student.Status status);

    /** B3 review list search: name (either order) or matric number. */
    @Query("select s.id from Student s where lower(concat(concat(s.firstName, ' '), s.surname)) like ?1 or lower(concat(concat(s.surname, ' '), s.firstName)) like ?1 or s.matricNumber like ?2")
    List<UUID> searchIds(String nameLike, String matricLike);
}
