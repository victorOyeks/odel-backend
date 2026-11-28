package com.odel.registration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.registration.dto.RegistrationDtos.*;
import com.odel.registration.dto.RequestDtos.*;
import com.odel.registration.dto.StaffDtos.CarryOverView;
import com.odel.registration.entity.*;

import java.time.LocalDate;
import java.util.*;

/** Entity → response mapping shared by the student and staff screens. */
public final class Views {
    static final ObjectMapper JSON = new ObjectMapper();

    private Views() {}

    public static String kindLabel(RegistrationCourse.Kind k) {
        return switch (k) { case COMPULSORY -> "Core"; case ELECTIVE -> "Elective"; case CARRY_OVER -> "Carry-over"; };
    }

    public static SemesterView semester(Registration r) {
        return new SemesterView(r.getSemesterId(), r.getSessionId(), r.getSessionName(), r.getSemesterNumber(),
                CalendarService.label(r.getSessionName(), r.getSemesterNumber()), r.getSemesterNumber() == 1 ? "First semester" : "Second semester");
    }

    public static SemesterView semester(RegistrationRequest r) {
        return new SemesterView(r.getSemesterId(), null, r.getSessionName(), r.getSemesterNumber(),
                CalendarService.label(r.getSessionName(), r.getSemesterNumber()), r.getSemesterNumber() == 1 ? "First semester" : "Second semester");
    }

    public static List<RegistrationCourse> ordered(List<RegistrationCourse> courses) {
        List<RegistrationCourse> l = new ArrayList<>(courses);
        l.sort(Comparator.comparing((RegistrationCourse c) -> CourseRules.rank(CourseRules.Kind.valueOf(c.getKind().name()))).thenComparing(RegistrationCourse::getCode));
        return l;
    }

    public static RegistrationView registration(Registration r, List<RegistrationCourse> courses, List<RegistrationEvent> events,
                                                LocalDate changesCloseOn, LocalDate today, boolean changeWaiting) {
        List<CourseLine> lines = ordered(courses).stream()
                .map(c -> new CourseLine(c.getCourseId(), c.getCode(), c.getTitle(), c.getUnits(), c.getKind().name(), kindLabel(c.getKind()))).toList();
        List<HistoryItem> history = events == null ? null : events.stream()
                .map(e -> new HistoryItem(e.getCreatedAt(), e.getAction(), e.getSummary(), e.getActorName(), e.getSlipVersion())).toList();
        boolean canChange = changesCloseOn != null && today != null && !today.isAfter(changesCloseOn) && !changeWaiting;
        return new RegistrationView(r.getId(), semester(r), r.getStatus().name(), r.isLate(), r.getSlipNumber(), r.getSlipVersion(),
                r.getVerificationCode(), lines, r.getCourseCount(), r.getTotalUnits(), r.getSubmittedAt(), r.getUpdatedAt(), changesCloseOn, canChange,
                new Ref(r.getCentreId(), r.getCentreCode(), r.getCentreName()), history);
    }

    public static String statusLabel(RegistrationRequest.Status s) {
        return switch (s) {
            case PENDING -> "Waiting for a decision";
            case APPROVED -> "Approved";
            case DECLINED -> "Not approved";
            case CANCELLED -> "Cancelled";
        };
    }

    public static final Map<String, String> REASONS = new LinkedHashMap<>();

    static {
        REASONS.put("MEDICAL", "Medical");
        REASONS.put("BEREAVEMENT", "Bereavement");
        REASONS.put("WORK", "Work");
        REASONS.put("TRAVEL", "Travel");
        REASONS.put("FEES", "Fees");
        REASONS.put("TECHNICAL", "Technical problem");
        REASONS.put("OTHER", "Other");
    }

    public static List<CourseRef> courses(String json) {
        if (json == null) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<CourseRef>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String changeSummary(List<CourseRef> drop, List<CourseRef> add) {
        List<String> parts = new ArrayList<>();
        if (!drop.isEmpty()) parts.add("drop " + String.join(", ", drop.stream().map(CourseRef::code).toList()));
        if (!add.isEmpty()) parts.add("add " + String.join(", ", add.stream().map(CourseRef::code).toList()));
        String s = String.join(", ", parts);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String sizeLabel(Long size) {
        if (size == null) return null;
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return Math.round(size / 1024.0) + " KB";
        return String.format(Locale.UK, "%.1f MB", size / (1024.0 * 1024.0));
    }

    public static RequestView request(RegistrationRequest r, boolean hasCoordinator, Integer maxUnits, LocalDate registeredOn) {
        AttachmentView att = r.getAttachmentKey() == null ? null : new AttachmentView(r.getAttachmentName(), r.getAttachmentSize(),
                sizeLabel(r.getAttachmentSize()), r.getAttachmentType(), type(r.getAttachmentName()));
        ChangeView change = null;
        if (r.getKind() == RegistrationRequest.Kind.CHANGE) {
            List<CourseRef> drop = courses(r.getDropCourses()), add = courses(r.getAddCourses());
            change = new ChangeView(drop, add, r.getUnitsBefore(), r.getUnitsAfter(), maxUnits, changeSummary(drop, add));
        }
        DecisionView decision = r.getDecidedAt() == null ? null : new DecisionView(r.getDecidedAt(), r.getDecidedByName(), r.getDecidedByRole(), r.getDecisionNote());
        return new RequestView(r.getId(), r.getKind().name(), r.getStatus().name(), statusLabel(r.getStatus()), semester(r),
                new StudentBrief(r.getStudentId(), r.getMatricNumber(), r.getStudentName(), r.getProgrammeName(), r.getLevel(),
                        r.getLevel() == null ? null : r.getLevel() + "L"),
                new CentreBrief(r.getCentreId(), r.getCentreName(), hasCoordinator), r.getReasonCategory(),
                r.getReasonCategory() == null ? null : REASONS.getOrDefault(r.getReasonCategory(), r.getReasonCategory()), r.getReason(), att, change,
                r.getRegisterBy(), decision, r.getCreatedAt(), registeredOn);
    }

    static String type(String fileName) {
        if (fileName == null || !fileName.contains(".")) return null;
        return fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
    }

    public static CarryOverView carryOver(CarryOver c) {
        return new CarryOverView(c.getId(), c.getStudentId(), c.getCourseId(), c.getCourseCode(), c.getCourseTitle(), c.getUnits(), c.getSemester(),
                c.getFromSession(), c.getSource().name(), c.getStatus().name(), c.getCreatedAt(), c.getCreatedByName());
    }
}
