package com.odel.academic.repository;

import com.odel.academic.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped through @TenantId: queries only ever see the current university. */
public interface GradingRuleRepository extends JpaRepository<GradingRule, UUID> {
    @Query("select g from GradingRule g where g.programmeId is null order by g.version desc")
    List<GradingRule> defaultVersions();

    @Query("select g from GradingRule g where g.programmeId = ?1 order by g.version desc")
    List<GradingRule> programmeVersions(UUID programmeId);

    @Query("select g from GradingRule g where g.programmeId is not null order by g.version desc")
    List<GradingRule> allProgrammeVersions();
}
