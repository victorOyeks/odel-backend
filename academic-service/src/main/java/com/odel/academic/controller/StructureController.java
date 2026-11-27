package com.odel.academic.controller;

import com.odel.academic.dto.ImportDtos.*;
import com.odel.academic.dto.StructureDtos.*;
import com.odel.academic.entity.Department;
import com.odel.academic.entity.Faculty;
import com.odel.academic.entity.ImportJob;
import com.odel.academic.entity.Programme;
import com.odel.academic.service.ImportService;
import com.odel.academic.service.StructureService;
import com.odel.academic.client.IdentityClient;
import com.odel.common.security.CurrentUserArg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** W-12 … W-12j Academic structure: faculties, departments, programmes, and the structure CSV import (W-12c). */
@RestController
@RequestMapping("/api/v1/academic")
public class StructureController {
    private final StructureService structure;
    private final ImportService imports;
    private final IdentityClient identity;

    public StructureController(StructureService structure, ImportService imports, IdentityClient identity) {
        this.structure = structure;
        this.imports = imports;
        this.identity = identity;
    }

    @GetMapping("/structure")
    public Tree tree(CurrentUserArg.Holder cu) {
        Guard.staff(cu.user());
        return structure.tree();
    }

    @PostMapping("/faculties")
    public ResponseEntity<FacultyView> addFaculty(CurrentUserArg.Holder cu, @RequestBody FacultyIn body) {
        Guard.write(cu.user());
        return ResponseEntity.status(HttpStatus.CREATED).body(FacultyView.of(structure.saveFaculty(null, body, identity.actor(cu.user()))));
    }

    @PutMapping("/faculties/{id}")
    public FacultyView editFaculty(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody FacultyIn body) {
        Guard.write(cu.user());
        return FacultyView.of(structure.saveFaculty(id, body, identity.actor(cu.user())));
    }

    @PostMapping("/departments")
    public ResponseEntity<DepartmentView> addDepartment(CurrentUserArg.Holder cu, @RequestBody DepartmentIn body) {
        Guard.write(cu.user());
        return ResponseEntity.status(HttpStatus.CREATED).body(DepartmentView.of(structure.saveDepartment(null, body, identity.actor(cu.user()))));
    }

    @PutMapping("/departments/{id}")
    public DepartmentView editDepartment(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody DepartmentIn body) {
        Guard.write(cu.user());
        return DepartmentView.of(structure.saveDepartment(id, body, identity.actor(cu.user())));
    }

    @PostMapping("/programmes")
    public ResponseEntity<ProgrammeDetail> addProgramme(CurrentUserArg.Holder cu, @RequestBody ProgrammeIn body) {
        Guard.write(cu.user());
        Programme p = structure.saveProgramme(null, body, identity.actor(cu.user()));
        return ResponseEntity.status(HttpStatus.CREATED).body(structure.programme(p.getId()));
    }

    @PutMapping("/programmes/{id}")
    public ProgrammeDetail editProgramme(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody ProgrammeIn body) {
        Guard.write(cu.user());
        structure.saveProgramme(id, body, identity.actor(cu.user()));
        return structure.programme(id);
    }

    @GetMapping("/programmes/{id}")
    public ProgrammeDetail programme(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.staff(cu.user());
        return structure.programme(id);
    }

    @PostMapping("/structure/imports")
    public ImportView checkImport(CurrentUserArg.Holder cu, @RequestBody CheckRequest body) {
        Guard.write(cu.user());
        return imports.check(ImportJob.Kind.STRUCTURE, body, identity.actor(cu.user()));
    }
}
