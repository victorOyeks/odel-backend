package com.odel.student.service;

import com.odel.common.ApiException;
import com.odel.student.dto.DirectoryDtos.Centre;
import com.odel.student.dto.DirectoryDtos.Directory;
import com.odel.student.dto.DirectoryDtos.Entry;
import com.odel.student.dto.DirectoryDtos.Programme;
import com.odel.student.entity.Student;
import com.odel.student.repository.StudentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** The students of the current university as other services need them (no history, no account details). */
@Service
public class DirectoryService {
    private final StudentRepository students;

    public DirectoryService(StudentRepository students) {
        this.students = students;
    }

    @Transactional(readOnly = true)
    public Directory all() {
        return new Directory(students.findAll(Sort.by("matricNumber")).stream().map(DirectoryService::entry).toList());
    }

    @Transactional(readOnly = true)
    public Entry one(UUID id) {
        return students.findInTenant(id).map(DirectoryService::entry).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public Entry byUser(UUID userId) {
        return students.findByUserId(userId).map(DirectoryService::entry).orElseThrow(ApiException::notFound);
    }

    static Entry entry(Student s) {
        return new Entry(s.getId(), s.getUserId(), s.getMatricNumber(), s.displayName(), s.getFirstName(), s.getEmail(),
                new Programme(s.getProgrammeCode(), s.getProgrammeName()), s.getLevel(), s.getLevel() + "L",
                s.getCentreId() == null && s.getCentreName() == null ? null
                        : new Centre(s.getCentreId(), s.getCentreCode(), s.getCentreName(), s.getCentreShortName()),
                s.getStatus().name());
    }
}
