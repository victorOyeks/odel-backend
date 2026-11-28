package com.odel.student.service;

import com.odel.common.ApiException;
import com.odel.common.PageResponse;
import com.odel.common.audit.AuditService;
import com.odel.common.files.StorageService;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.student.client.IdentityClient;
import com.odel.student.dto.IdentityDtos;
import com.odel.student.dto.ProfileDtos.*;
import com.odel.student.dto.StudentDtos;
import com.odel.student.entity.ProfileChange;
import com.odel.student.entity.ProfileRequest;
import com.odel.student.entity.Student;
import com.odel.student.repository.ProfileChangeRepository;
import com.odel.student.repository.ProfileRequestRepository;
import com.odel.student.repository.StudentRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The ODeL office's side of B3 profile changes: the review list of corrections and photos, approve (applied at once) or decline
 * with a reason, and the record's change history (W-22b). ODeL admin decides; the ODeL director reads.
 */
@Service
public class ProfileReviewService {
    private final ProfileRequestRepository requests;
    private final ProfileChangeRepository changes;
    private final StudentRepository students;
    private final ProfileHistory history;
    private final IdentityClient identity;
    private final StorageService storage;
    private final ActorService actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ProfileReviewService(ProfileRequestRepository requests, ProfileChangeRepository changes, StudentRepository students, ProfileHistory history,
                                IdentityClient identity, StorageService storage, ActorService actors, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.requests = requests;
        this.changes = changes;
        this.students = students;
        this.history = history;
        this.identity = identity;
        this.storage = storage;
        this.actors = actors;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public ReviewPage list(CurrentUser cu, String status, String kind, String q, int page, int size) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        String st = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
        ProfileRequest.Status statusF = null;
        if (!st.equals("ALL")) {
            try {
                statusF = ProfileRequest.Status.valueOf(st);
            } catch (IllegalArgumentException e) {
                throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Choose a status.", Map.of("status", "Use PENDING, APPROVED, DECLINED, WITHDRAWN or ALL."));
            }
        }
        ProfileRequest.Kind kindF = null;
        if (kind != null && !kind.isBlank()) {
            try {
                kindF = ProfileRequest.Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Choose a kind.", Map.of("kind", "Use CORRECTION or PHOTO."));
            }
        }
        int p = Math.max(page, 0), s = Math.min(Math.max(size, 1), 100);
        ProfileRequest.Status fs = statusF;
        ProfileRequest.Kind fk = kindF;
        return tx.execute(t -> {
            List<UUID> ids = null;
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                String matric = "%" + q.replaceAll("\\s+", "").toUpperCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                ids = students.searchIds(like, matric);
            }
            List<UUID> only = ids;
            Specification<ProfileRequest> spec = (root, query, cb) -> {
                List<Predicate> ps = new ArrayList<>();
                if (fs != null) ps.add(cb.equal(root.get("status"), fs));
                if (fk != null) ps.add(cb.equal(root.get("kind"), fk));
                if (only != null) ps.add(only.isEmpty() ? cb.disjunction() : root.get("studentId").in(only));
                return cb.and(ps.toArray(new Predicate[0]));
            };
            Sort sort = fs == ProfileRequest.Status.PENDING ? Sort.by("submittedAt").ascending() : Sort.by("submittedAt").descending();
            Page<ProfileRequest> found = requests.findAll(spec, PageRequest.of(p, s, sort));
            Map<UUID, Student> byId = students.findAllInTenant(found.stream().map(ProfileRequest::getStudentId).collect(Collectors.toSet()))
                    .stream().collect(Collectors.toMap(Student::getId, Function.identity()));
            List<ReviewItem> items = found.stream().map(r -> {
                RequestView v = ProfileService.view(r);
                return new ReviewItem(r.getId(), v.kind(), v.field(), v.fieldLabel(), v.currentValue(), v.requestedValue(), v.status(), v.statusLabel(),
                        r.getSubmittedAt(), brief(byId.get(r.getStudentId())), r.getKind() == ProfileRequest.Kind.CORRECTION && r.getFileKey() != null);
            }).toList();
            Counts counts = new Counts(requests.countByStatus(ProfileRequest.Status.PENDING),
                    requests.countByStatusAndKind(ProfileRequest.Status.PENDING, ProfileRequest.Kind.CORRECTION),
                    requests.countByStatusAndKind(ProfileRequest.Status.PENDING, ProfileRequest.Kind.PHOTO));
            return new ReviewPage(items, p, s, found.getTotalElements(), counts);
        });
    }

    public ReviewDetail one(CurrentUser cu, UUID id) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return tx.execute(t -> detail(find(id)));
    }

    public Link proof(CurrentUser cu, UUID id) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        ProfileRequest r = tx.execute(t -> find(id));
        if (r.getFileKey() == null) throw new ApiException(HttpStatus.NOT_FOUND, "no_file", "This request has no file.");
        Duration ttl = Duration.ofMinutes(5);
        return new Link(storage.presignGet(r.getFileKey(), ttl), clock.instant().plus(ttl));
    }

    public ReviewDetail approve(CurrentUser cu, UUID id, String note) {
        Access.require(cu, Role.ODEL_ADMIN);
        AuditService.Actor actor = actors.actor(cu);
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > 500) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Use at most 500 characters.", Map.of("note", "Use at most 500 characters."));
        record Applied(ProfileRequest r, Student s, String sentence) {}
        Applied a = tx.execute(t -> {
            ProfileRequest r = find(id);
            if (r.getStatus() != ProfileRequest.Status.PENDING) throw ProfileService.notPending(r);
            Student s = students.findInTenant(r.getStudentId()).orElseThrow(ApiException::notFound);
            String sentence;
            if (r.getKind() == ProfileRequest.Kind.PHOTO) {
                String old = s.getPhotoKey();
                s.setPhotoKey(r.getFileKey());
                s.setPhotoUpdatedAt(clock.instant());
                history.record(s, "PHOTO", old == null ? null : "previous photo", "new photo", ProfileChange.Source.REQUEST, r.getId(), cu.userId(), actor.name());
                sentence = "Your new photo is now on your record. Exam slips and attendance lists show it from now on.";
            } else {
                String old = ProfileService.current(s, r.getField());
                String v = r.getRequestedValue();
                switch (r.getField()) {
                    case SURNAME -> s.setSurname(v);
                    case FIRST_NAME -> s.setFirstName(v);
                    case OTHER_NAMES -> s.setOtherNames(v == null || v.isEmpty() ? null : v);
                    case DATE_OF_BIRTH -> s.setDateOfBirth(LocalDate.parse(v));
                    case GENDER -> s.setGender(v);
                }
                history.record(s, r.getField().name(), old, v, ProfileChange.Source.REQUEST, r.getId(), cu.userId(), actor.name());
                boolean name = r.getField() == ProfileRequest.Field.SURNAME || r.getField() == ProfileRequest.Field.FIRST_NAME || r.getField() == ProfileRequest.Field.OTHER_NAMES;
                if (name && s.getUserId() != null) identity.change(s.getUserId(), new IdentityDtos.ChangeRequest(null, s.displayName(), null, null));
                String label = ProfileRules.fieldLabel(r.getField().name()).toLowerCase(Locale.ROOT);
                sentence = "Your " + label + " is now " + ProfileService.display(r.getField(), v) + " on your record."
                        + (name ? " Exam slips, results and your transcript use it from now on." : "");
            }
            s.setUpdatedAt(clock.instant());
            students.save(s);
            r.setStatus(ProfileRequest.Status.APPROVED);
            r.setDecidedAt(clock.instant());
            r.setDecidedBy(cu.userId());
            r.setDecidedByName(actor.name());
            r.setDecisionNote(n);
            requests.save(r);
            audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_PROFILE_REQUEST_APPROVED", "profile_request", r.getId(),
                    actor.name() + " approved " + s.displayName() + "'s request (" + ProfileService.what(r) + ")",
                    r.getCurrentValue() == null ? null : Map.of("value", r.getCurrentValue()),
                    r.getRequestedValue() == null ? null : Map.of("value", r.getRequestedValue())));
            return new Applied(r, s, sentence);
        });
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("firstName", a.s().getFirstName());
        v.put("what", a.r().getKind() == ProfileRequest.Kind.PHOTO ? "your new photo" : "the correction to your " + ProfileRules.fieldLabel(a.r().getField().name()).toLowerCase(Locale.ROOT));
        v.put("result", a.sentence());
        v.put("decidedBy", actorName(a.r()));
        v.put("note", n == null ? "" : n);
        history.send(a.s(), "student.request_approved", v);
        return tx.execute(t -> detail(find(id)));
    }

    public ReviewDetail decline(CurrentUser cu, UUID id, String note) {
        Access.require(cu, Role.ODEL_ADMIN);
        AuditService.Actor actor = actors.actor(cu);
        String n = note == null ? "" : note.trim();
        if (n.length() < 5 || n.length() > 500) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Tell the student why, and what to send instead (5 to 500 characters).",
                    Map.of("note", "Tell the student why, and what to send instead (5 to 500 characters)."));
        }
        record Declined(ProfileRequest r, Student s) {}
        Declined d = tx.execute(t -> {
            ProfileRequest r = find(id);
            if (r.getStatus() != ProfileRequest.Status.PENDING) throw ProfileService.notPending(r);
            Student s = students.findInTenant(r.getStudentId()).orElseThrow(ApiException::notFound);
            r.setStatus(ProfileRequest.Status.DECLINED);
            r.setDecidedAt(clock.instant());
            r.setDecidedBy(cu.userId());
            r.setDecidedByName(actor.name());
            r.setDecisionNote(n);
            requests.save(r);
            audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_PROFILE_REQUEST_DECLINED", "profile_request", r.getId(),
                    actor.name() + " declined " + s.displayName() + "'s request (" + ProfileService.what(r) + "): " + n, null, null));
            return new Declined(r, s);
        });
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("firstName", d.s().getFirstName());
        v.put("what", d.r().getKind() == ProfileRequest.Kind.PHOTO ? "your new photo" : "the correction to your " + ProfileRules.fieldLabel(d.r().getField().name()).toLowerCase(Locale.ROOT));
        v.put("decidedBy", actorName(d.r()));
        v.put("note", n);
        history.send(d.s(), "student.request_declined", v);
        return tx.execute(t -> detail(find(id)));
    }

    public PageResponse<ChangeView> changes(CurrentUser cu, UUID studentId, int page, int size) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        int p = Math.max(page, 0), s = Math.min(Math.max(size, 1), 100);
        return tx.execute(t -> {
            students.findInTenant(studentId).orElseThrow(ApiException::notFound);
            Page<ProfileChange> found = changes.findByStudentIdOrderByCreatedAtDesc(studentId, PageRequest.of(p, s));
            return new PageResponse<>(found.stream().map(ProfileReviewService::view).toList(), p, s, found.getTotalElements());
        });
    }

    static ChangeView view(ProfileChange c) {
        String sourceLabel = switch (c.getSource()) {
            case STUDENT -> "EMAIL".equals(c.getField()) ? "By the student (code sent to the new address)" : "By the student";
            case REQUEST -> "Approved by the ODeL office";
            case OFFICE -> "By the ODeL office";
        };
        return new ChangeView(c.getCreatedAt(), c.getField(), ProfileRules.fieldLabel(c.getField()), c.getOldValue(), c.getNewValue(), c.getSource().name(),
                sourceLabel, new StudentDtos.Person(c.getActorName() == null ? "System" : c.getActorName()), c.getRequestId());
    }

    private ReviewDetail detail(ProfileRequest r) {
        Student s = students.findInTenant(r.getStudentId()).orElseThrow(ApiException::notFound);
        String current = null, next = null;
        if (r.getKind() == ProfileRequest.Kind.PHOTO) {
            current = s.getPhotoKey() == null || s.getPhotoKey().equals(r.getFileKey()) && r.getStatus() == ProfileRequest.Status.APPROVED ? null
                    : storage.presignGet(s.getPhotoKey(), Duration.ofMinutes(10));
            next = storage.presignGet(r.getFileKey(), Duration.ofMinutes(10));
        }
        return new ReviewDetail(ProfileService.view(r), brief(s), current, next);
    }

    private ProfileRequest find(UUID id) {
        return requests.findInTenant(id).orElseThrow(ApiException::notFound);
    }

    private static StudentBrief brief(Student s) {
        if (s == null) return null;
        return new StudentBrief(s.getId(), s.getMatricNumber(), s.displayName(), new StudentDtos.CentreView(s.getCentreCode(), s.getCentreName(), s.getCentreShortName()));
    }

    private static String actorName(ProfileRequest r) {
        return r.getDecidedByName() == null ? "The ODeL office" : r.getDecidedByName();
    }
}
