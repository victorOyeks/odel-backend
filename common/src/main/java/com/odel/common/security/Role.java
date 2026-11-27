package com.odel.common.security;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

public enum Role {
    SUPER_ADMIN("Platform admin"),
    PLATFORM_SUPPORT("Platform support"),
    MANAGEMENT("University management"),
    ODEL_DIRECTOR("ODeL director"),
    ODEL_ADMIN("ODeL admin"),
    CENTRE_COORDINATOR("Centre coordinator"),
    LECTURER("Lecturer"),
    FACILITATOR("Facilitator"),
    EXAM_OFFICER("Exam officer"),
    HOD("Head of Department"),
    DEAN("Dean"),
    REGISTRY("Registry officer"),
    COUNSELLOR("Student counsellor"),
    STUDENT("Student");

    private final String label;

    Role(String label) { this.label = label; }

    public String label() { return label; }

    public boolean isPlatformRole() {
        return this == SUPER_ADMIN || this == PLATFORM_SUPPORT;
    }
}
