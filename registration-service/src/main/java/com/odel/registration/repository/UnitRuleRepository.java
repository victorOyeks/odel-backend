package com.odel.registration.repository;

import com.odel.registration.entity.UnitRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UnitRuleRepository extends JpaRepository<UnitRule, UUID> {
    @Query("select r from UnitRule r where r.programmeId is null order by r.level")
    List<UnitRule> defaults();

    @Query("select r from UnitRule r where r.programmeId = ?1 order by r.level")
    List<UnitRule> forProgramme(UUID programmeId);

    @Query("select distinct r.programmeId from UnitRule r where r.programmeId is not null")
    List<UUID> programmesWithOwnRules();

    @Modifying
    @Query("delete from UnitRule r where r.programmeId = ?1")
    void deleteForProgramme(UUID programmeId);
}
