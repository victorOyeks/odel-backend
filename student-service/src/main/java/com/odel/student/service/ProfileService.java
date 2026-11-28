package com.odel.student.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.files.StorageService;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.student.client.IdentityClient;
import com.odel.student.dto.IdentityDtos;
import com.odel.student.dto.ProfileDtos.*;
import com.odel.student.dto.StudentDtos;
import com.odel.student.entity.ProfileChange;
import com.odel.student.entity.ProfileRequest;
import com.odel.student.entity.Student;
import com.odel.student.repository.ProfileRequestRepository;
import com.odel.student.repository.StudentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The student's own profile page (B3, L-14 … L-14i): contact details she changes herself (phone with her password, home address,
 * next of kin), the photo and name / date-of-birth / gender corrections she asks the ODeL office for, and her requests.
 * The login email and password live in identity-service (/me/email-change, /me/password); a confirmed email change arrives here.
 */
@Service
public class ProfileService {
    static final long MAX_PHOTO = 2L * 1024 * 1024;
    static final long MAX_PROOF = 5L * 1024 * 1024;
    static final Map<String, String> PHOTO_TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png");
    static final Map<String, String> PROOF_TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "pdf", "application/pdf");
    /** M-06 "Check your details" comes straight after activation: no password asked for the phone within this time. */
    static final Duration FIRST_PROFILE = Duration.ofHours(1);

    private final StudentRepository students;
    private final ProfileRequestRepository requests;
    private final ProfileHistory history;
    private final com.odel.student.repository.ProfileChangeRepository changes;
    private final IdentityClient identity;
    private final StorageService storage;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ProfileService(StudentRepository students, ProfileRequestRepository requests, ProfileHistory history, IdentityClient identity,
                          StorageService storage, AuditService audit, TransactionTemplate tx, Clock clock,
                          com.odel.student.repository.ProfileChangeRepository changes) {
        this.changes = changes;
        this.students = students;
        this.requests = requests;
        this.history = history;
        this.identity = identity;
        this.storage = storage;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- L-14

    public MyProfile profile(CurrentUser cu) {
        Student s = mine(cu);
        return tx.execute(t -> view(students.findInTenant(s.getId()).orElseThrow()));
    }

    /** PATCH /student-profile: phone (password unless just activated), address and next of kin. Only the keys sent change. */
    public MyProfile update(CurrentUser cu, Map<String, Object> body) {
        Student s = mine(cu);
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, String> f = new LinkedHashMap<>();

        boolean phoneSent = in.containsKey("phone");
        String phone = s.getPhone();
        if (phoneSent) {
            String raw = str(in.get("phone"));
            if (raw.isBlank()) phone = null;
            else {
                phone = ImportValidator.phone(raw);
                if (phone == null || !phone.startsWith("07") && !phone.startsWith("08") && !phone.startsWith("09")) f.put("phone", ProfileRules.PHONE_HINT);
            }
        }
        boolean addressSent = in.containsKey("address");
        Address address = addressSent ? address(in.get("address"), f) : null;
        boolean kinSent = in.containsKey("nextOfKin");
        NextOfKin kin = kinSent ? kin(in.get("nextOfKin"), f) : null;
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);

        boolean phoneChanges = phoneSent && !Objects.equals(phone, s.getPhone());
        String oldAddress = ProfileRules.addressLine(s.getAddressLine(), s.getAddressTown(), s.getAddressState());
        String newAddress = address == null ? null : ProfileRules.addressLine(address.line(), address.town(), address.state());
        boolean addressChanges = addressSent && !Objects.equals(oldAddress, newAddress);
        String oldKin = ProfileRules.nextOfKinLine(s.getNextOfKinName(), s.getNextOfKinRelationship(), s.getNextOfKinPhone());
        String newKin = kin == null ? null : ProfileRules.nextOfKinLine(kin.name(), kin.relationship(), kin.phone());
        boolean kinChanges = kinSent && !Objects.equals(oldKin, newKin);
        if (!phoneChanges && !addressChanges && !kinChanges) {
            if (phoneSent && !addressSent && !kinSent) {
                throw ApiException.fields(HttpStatus.BAD_REQUEST, "no_change", "That is already your phone number.", Map.of("phone", "That is already your phone number."));
            }
            return profile(cu);
        }
        if (phoneChanges && !justActivated(s)) identity.checkPassword(s.getUserId(), str(in.get("password")));

        String newPhone = phone;
        List<String> lines = new ArrayList<>();
        tx.executeWithoutResult(t -> {
            Student x = students.findInTenant(s.getId()).orElseThrow(ApiException::notFound);
            Map<String, Object> oldV = new LinkedHashMap<>(), newV = new LinkedHashMap<>();
            if (phoneChanges) {
                history.record(x, "PHONE", x.getPhone(), newPhone, ProfileChange.Source.STUDENT, null, cu.userId(), x.displayName());
                oldV.put("phone", x.getPhone());
                newV.put("phone", newPhone);
                x.setPhone(newPhone);
                lines.add("Phone number: " + (newPhone == null ? "removed" : ProfileRules.maskPhone(newPhone)));
                if (x.getUserId() != null) identity.change(x.getUserId(), new IdentityDtos.ChangeRequest(null, null, null, newPhone == null ? "" : newPhone));
            }
            if (addressChanges) {
                history.record(x, "ADDRESS", oldAddress, newAddress, ProfileChange.Source.STUDENT, null, cu.userId(), x.displayName());
                oldV.put("address", oldAddress);
                newV.put("address", newAddress);
                x.setAddressLine(address == null ? null : address.line());
                x.setAddressTown(address == null ? null : address.town());
                x.setAddressState(address == null ? null : address.state());
                lines.add("Home address: " + (newAddress == null ? "removed" : newAddress));
            }
            if (kinChanges) {
                history.record(x, "NEXT_OF_KIN", oldKin, newKin, ProfileChange.Source.STUDENT, null, cu.userId(), x.displayName());
                oldV.put("nextOfKin", oldKin);
                newV.put("nextOfKin", newKin);
                x.setNextOfKinName(kin == null ? null : kin.name());
                x.setNextOfKinRelationship(kin == null ? null : kin.relationship());
                x.setNextOfKinPhone(kin == null ? null : kin.phone());
                lines.add("Next of kin: " + (newKin == null ? "removed" : kin.name() + (kin.relationship() == null ? "" : " (" + kin.relationship() + ")")));
            }
            x.setUpdatedAt(clock.instant());
            students.save(x);
            audit.record(new AuditService.Entry(x.getTenantId(), studentActor(x, cu), "STUDENT_CONTACT_CHANGED", "student", x.getId(),
                    x.displayName() + " changed " + String.join(", ", newV.keySet()) + " on the profile page", oldV, newV));
        });
        Student saved = tx.execute(t -> students.findInTenant(s.getId()).orElseThrow());
        history.tellContactChanged(saved, lines, false);
        return tx.execute(t -> view(students.findInTenant(s.getId()).orElseThrow()));
    }

    /** M-06: the first phone save within an hour of activation needs no password (she has just set it); every later change does. */
    private boolean justActivated(Student s) {
        return s.getActivatedAt() != null && s.getActivatedAt().plus(FIRST_PROFILE).isAfter(clock.instant())
                && !tx.execute(t -> changes.existsByStudentIdAndFieldAndSource(s.getId(), "PHONE", ProfileChange.Source.STUDENT));
    }

    private static Address address(Object raw, Map<String, String> f) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            f.put("address", "Enter your home address.");
            return null;
        }
        String line = str(m.get("line")).trim().replaceAll("\\s+", " "), town = str(m.get("town")).trim().replaceAll("\\s+", " ");
        String state = ProfileRules.state(str(m.get("state")));
        if (line.isEmpty()) f.put("address.line", "Enter your house number and street.");
        else if (line.length() > 200) f.put("address.line", "Use at most 200 characters.");
        if (town.isEmpty()) f.put("address.town", "Enter your town or city.");
        else if (town.length() > 100) f.put("address.town", "Use at most 100 characters.");
        if (state == null) f.put("address.state", "Choose your state.");
        return new Address(line, town, state);
    }

    private static NextOfKin kin(Object raw, Map<String, String> f) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            f.put("nextOfKin", "Enter your next of kin.");
            return null;
        }
        String[] problem = new String[1];
        String name = ProfileRules.name(str(m.get("name")), 150, false, problem);
        if (name == null || name.length() < 2) f.put("nextOfKin.name", problem[0] != null ? problem[0] : "Enter your next of kin's full name.");
        String rel = str(m.get("relationship")).trim();
        if (rel.isEmpty()) f.put("nextOfKin.relationship", "Say how they are related to you, like Mother or Brother.");
        else if (rel.length() > 50) f.put("nextOfKin.relationship", "Use at most 50 characters.");
        String phone = ImportValidator.phone(str(m.get("phone")));
        if (phone == null) f.put("nextOfKin.phone", ProfileRules.PHONE_HINT);
        return new NextOfKin(name, rel, phone);
    }

    // ---------------------------------------------------------------- uploads, photo (L-14h)

    public UploadLink upload(CurrentUser cu, UploadRequest in) {
        Student s = mine(cu);
        boolean photo = "PHOTO".equalsIgnoreCase(in.purpose());
        if (!photo && !"PROOF".equalsIgnoreCase(in.purpose())) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Say what the file is for (PHOTO or PROOF).", Map.of("purpose", "Use PHOTO or PROOF."));
        }
        Map<String, String> types = photo ? PHOTO_TYPES : PROOF_TYPES;
        String name = in.fileName() == null ? "" : in.fileName().trim();
        String ext = ext(name);
        Map<String, String> f = new LinkedHashMap<>();
        if (name.isEmpty()) f.put("fileName", "Choose a file.");
        else if (!types.containsKey(ext)) f.put("fileName", photo ? "Choose a JPG or PNG photo." : "Attach a JPG, PNG or PDF file.");
        if (in.size() != null && in.size() > (photo ? MAX_PHOTO : MAX_PROOF)) {
            f.put("size", photo ? "The photo is over 2 MB. Choose a smaller one." : "The file is over 5 MB. Scan it at a lower resolution or take a photo.");
        }
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        String key = prefix(s) + (photo ? "photo-" : "proof-") + UUID.randomUUID() + "." + ext;
        Duration ttl = Duration.ofMinutes(15);
        return new UploadLink(key, storage.presignPut(key, ttl), "PUT", Map.of("Content-Type", types.get(ext)), clock.instant().plus(ttl));
    }

    public RequestView photo(CurrentUser cu, PhotoRequest in) {
        Student s = mine(cu);
        String key = in == null || in.key() == null ? "" : in.key();
        if (!key.startsWith(prefix(s) + "photo-") || key.contains("..") || !PHOTO_TYPES.containsKey(ext(key)) || storage.size(key).isEmpty()) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Upload the photo again.", Map.of("key", "Upload the photo again."));
        }
        long size = storage.size(key).getAsLong();
        if (size > MAX_PHOTO) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The photo is over 2 MB. Choose a smaller one.",
                Map.of("key", "The photo is over 2 MB. Choose a smaller one."));
        ProfileRequest r = tx.execute(t -> {
            for (ProfileRequest old : requests.findByStudentIdAndStatus(s.getId(), ProfileRequest.Status.PENDING)) {
                if (old.getKind() != ProfileRequest.Kind.PHOTO) continue;
                old.setStatus(ProfileRequest.Status.WITHDRAWN);
                old.setDecidedAt(clock.instant());
                old.setDecisionNote("Replaced by a newer photo");
                requests.saveAndFlush(old);
            }
            ProfileRequest n = base(s, ProfileRequest.Kind.PHOTO);
            n.setFileKey(key);
            n.setFileName("photo." + ext(key));
            n.setFileSize(size);
            n.setFileType(PHOTO_TYPES.get(ext(key)));
            requests.save(n);
            audit.record(new AuditService.Entry(s.getTenantId(), studentActor(s, cu), "STUDENT_PHOTO_REQUESTED", "profile_request", n.getId(),
                    s.displayName() + " sent a new photo for the ODeL office to approve", null, null));
            return n;
        });
        received(s, "your new photo");
        history.tellOffice(s, "a new photo");
        return view(r);
    }

    // ---------------------------------------------------------------- corrections (L-14i)

    public List<CorrectionField> correctionFields(CurrentUser cu) {
        Student s = mine(cu);
        List<CorrectionField> out = new ArrayList<>();
        for (ProfileRequest.Field fl : ProfileRequest.Field.values()) {
            String cur = current(s, fl);
            out.add(new CorrectionField(fl.name(), fl.label(), fl == ProfileRequest.Field.GENDER ? ProfileRules.genderLabel(cur) : cur));
        }
        return out;
    }

    public RequestView correction(CurrentUser cu, CorrectionRequest in) {
        Student s = mine(cu);
        Map<String, String> f = new LinkedHashMap<>();
        ProfileRequest.Field field = null;
        try {
            field = in.field() == null ? null : ProfileRequest.Field.valueOf(in.field().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) { }
        String value = null;
        if (field == null) f.put("field", "Choose what is wrong.");
        else {
            String[] problem = new String[1];
            value = switch (field) {
                case SURNAME, FIRST_NAME -> ProfileRules.name(in.requestedValue(), 100, false, problem);
                case OTHER_NAMES -> ProfileRules.name(in.requestedValue(), 150, true, problem);
                case DATE_OF_BIRTH -> {
                    LocalDate d = ProfileRules.dateOfBirth(in.requestedValue(), LocalDate.now(clock.withZone(ZoneId.of("Africa/Lagos"))), problem);
                    yield d == null ? null : d.toString();
                }
                case GENDER -> {
                    String g = ProfileRules.gender(in.requestedValue());
                    if (g == null) problem[0] = "Choose Male or Female.";
                    yield g;
                }
            };
            if (value == null) f.put("requestedValue", problem[0]);
        }
        String note = in.note() == null ? null : in.note().trim();
        if (note != null && note.length() > 500) f.put("note", "Use at most 500 characters.");
        Proof p = in.proof();
        String key = p == null || p.key() == null ? "" : p.key();
        if (key.isBlank()) f.put("proof", "Add a document that shows the right " + (field == null ? "details" : ProfileRules.fieldLabel(field.name()).toLowerCase(Locale.ROOT)) + " (proof is required).");
        else if (!key.startsWith(prefix(s) + "proof-") || key.contains("..") || !PROOF_TYPES.containsKey(ext(key)) || storage.size(key).isEmpty()) f.put("proof", "Upload the file again.");
        else if (storage.size(key).getAsLong() > MAX_PROOF) f.put("proof", "The file is over 5 MB. Scan it at a lower resolution or take a photo.");
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);

        ProfileRequest.Field fl = field;
        String cur = current(s, fl);
        if (Objects.equals(emptyToNull(value), emptyToNull(cur))) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "no_change", "That is what we have on record now.", Map.of("requestedValue", "That is what we have on record now."));
        }
        boolean pending = tx.execute(t -> requests.findByStudentIdAndStatus(s.getId(), ProfileRequest.Status.PENDING).stream()
                .anyMatch(r -> r.getKind() == ProfileRequest.Kind.CORRECTION && r.getField() == fl));
        if (pending) {
            throw new ApiException(HttpStatus.CONFLICT, "request_pending", "You already asked to correct your "
                    + ProfileRules.fieldLabel(fl.name()).toLowerCase(Locale.ROOT) + ". Wait for the ODeL office, or withdraw that request first.");
        }
        String v = value;
        String fileName = p.fileName() == null || p.fileName().isBlank() ? key.substring(key.lastIndexOf('/') + 1) : p.fileName().trim();
        ProfileRequest r = tx.execute(t -> {
            ProfileRequest n = base(s, ProfileRequest.Kind.CORRECTION);
            n.setField(fl);
            n.setCurrentValue(cur);
            n.setRequestedValue(v);
            n.setNote(note == null || note.isEmpty() ? null : note);
            n.setFileKey(key);
            n.setFileName(fileName.length() > 300 ? fileName.substring(0, 300) : fileName);
            n.setFileSize(storage.size(key).getAsLong());
            n.setFileType(PROOF_TYPES.get(ext(key)));
            try {
                requests.saveAndFlush(n);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "request_pending", "You already asked to correct your "
                        + ProfileRules.fieldLabel(fl.name()).toLowerCase(Locale.ROOT) + ". Wait for the ODeL office, or withdraw that request first.");
            }
            audit.record(new AuditService.Entry(s.getTenantId(), studentActor(s, cu), "STUDENT_CORRECTION_REQUESTED", "profile_request", n.getId(),
                    s.displayName() + " asked to correct " + ProfileRules.fieldLabel(fl.name()).toLowerCase(Locale.ROOT) + ": \"" + display(fl, cur) + "\" → \"" + display(fl, v) + "\"",
                    cur == null ? null : Map.of("value", cur), Map.of("value", v)));
            return n;
        });
        received(s, "the correction to your " + ProfileRules.fieldLabel(fl.name()).toLowerCase(Locale.ROOT));
        history.tellOffice(s, "a correction to the " + ProfileRules.fieldLabel(fl.name()).toLowerCase(Locale.ROOT));
        return view(r);
    }

    // ---------------------------------------------------------------- her requests

    public Requests requests(CurrentUser cu) {
        Student s = mine(cu);
        return new Requests(tx.execute(t -> requests.findByStudentIdOrderBySubmittedAtDesc(s.getId())).stream().map(ProfileService::view).toList());
    }

    public RequestView request(CurrentUser cu, UUID id) {
        return view(own(mine(cu), id));
    }

    public Link proof(CurrentUser cu, UUID id) {
        ProfileRequest r = own(mine(cu), id);
        if (r.getFileKey() == null) throw new ApiException(HttpStatus.NOT_FOUND, "no_file", "This request has no file.");
        Duration ttl = Duration.ofMinutes(5);
        return new Link(storage.presignGet(r.getFileKey(), ttl), clock.instant().plus(ttl));
    }

    public RequestView withdraw(CurrentUser cu, UUID id) {
        Student s = mine(cu);
        ProfileRequest r = tx.execute(t -> {
            ProfileRequest x = requests.findInTenant(id).filter(q -> q.getStudentId().equals(s.getId())).orElseThrow(ApiException::notFound);
            if (x.getStatus() != ProfileRequest.Status.PENDING) throw notPending(x);
            x.setStatus(ProfileRequest.Status.WITHDRAWN);
            x.setDecidedAt(clock.instant());
            requests.save(x);
            audit.record(new AuditService.Entry(s.getTenantId(), studentActor(s, cu), "STUDENT_PROFILE_REQUEST_WITHDRAWN", "profile_request", x.getId(),
                    s.displayName() + " withdrew the request (" + what(x) + ")", null, null));
            return x;
        });
        return view(r);
    }

    // ---------------------------------------------------------------- internal: email confirmed in identity-service

    public void emailChanged(UUID userId, EmailChanged in) {
        tx.executeWithoutResult(t -> {
            Student s = students.findByUserId(userId).orElseThrow(ApiException::notFound);
            String old = s.getEmail();
            if (Objects.equals(old, in.email())) return;
            s.setEmail(in.email());
            s.setUpdatedAt(clock.instant());
            students.save(s);
            history.record(s, "EMAIL", old, in.email(), ProfileChange.Source.STUDENT, null, userId, s.displayName());
            audit.record(new AuditService.Entry(s.getTenantId(), new AuditService.Actor(userId, s.getTenantId(), s.displayName(), Role.STUDENT.name()),
                    "STUDENT_EMAIL_CHANGED", "student", s.getId(), s.displayName() + " changed their email (code sent to the new address)",
                    old == null ? null : Map.of("email", old), Map.of("email", in.email())));
        });
    }

    // ---------------------------------------------------------------- views and helpers

    MyProfile view(Student s) {
        List<ProfileRequest> open = requests.findByStudentIdAndStatus(s.getId(), ProfileRequest.Status.PENDING);
        PendingPhoto pending = open.stream().filter(r -> r.getKind() == ProfileRequest.Kind.PHOTO).findFirst()
                .map(r -> new PendingPhoto(r.getId(), storage.presignGet(r.getFileKey(), Duration.ofMinutes(10)), r.getSubmittedAt())).orElse(null);
        Address address = s.getAddressLine() == null && s.getAddressState() == null ? null : new Address(s.getAddressLine(), s.getAddressTown(), s.getAddressState());
        NextOfKin kin = s.getNextOfKinName() == null ? null : new NextOfKin(s.getNextOfKinName(), s.getNextOfKinRelationship(), s.getNextOfKinPhone());
        return new MyProfile(s.getId(), s.getMatricNumber(), s.displayName(), s.getFirstName(), s.initials(), s.getSurname(), s.getOtherNames(),
                s.getDateOfBirth(), s.getGender(), new StudentDtos.ProgrammeView(s.getProgrammeCode(), s.getProgrammeName()), s.getLevel(), s.getLevel() + "L",
                new StudentDtos.CentreView(s.getCentreCode(), s.getCentreName(), s.getCentreShortName()), s.getEmail(), ProfileRules.maskEmail(s.getEmail()),
                s.getPhone(), ProfileRules.maskPhone(s.getPhone()), address, kin, photo(s, storage), pending,
                open.stream().sorted(Comparator.comparing(ProfileRequest::getSubmittedAt)).map(ProfileService::view).toList(),
                s.getStatus().name(), s.getConsentAt() == null ? null : new StudentDtos.Consent(s.getConsentVersion(), s.getConsentAt()),
                new ReadOnly("Set by the university", "Only the ODeL office can change this"));
    }

    static Photo photo(Student s, StorageService storage) {
        return s.getPhotoKey() == null ? null : new Photo(storage.presignGet(s.getPhotoKey(), Duration.ofMinutes(10)), s.getPhotoUpdatedAt());
    }

    static RequestView view(ProfileRequest r) {
        FileView file = r.getFileKey() == null ? null : new FileView(r.getFileName(), r.getFileSize(), r.getFileSize() == null ? null : ProfileRules.size(r.getFileSize()),
                r.getFileType(), ext(r.getFileName() == null ? r.getFileKey() : r.getFileName()).toUpperCase(Locale.ROOT));
        Decision d = r.getStatus() == ProfileRequest.Status.APPROVED || r.getStatus() == ProfileRequest.Status.DECLINED
                ? new Decision(r.getDecidedAt(), r.getDecidedByName(), r.getDecisionNote()) : null;
        return new RequestView(r.getId(), r.getKind().name(), r.getField() == null ? null : r.getField().name(), r.getField() == null ? "Photo" : r.getField().label(),
                r.getCurrentValue(), r.getRequestedValue(), r.getNote(), file, r.getStatus().name(), r.getStatus().label(), r.getSubmittedAt(), d);
    }

    static String current(Student s, ProfileRequest.Field f) {
        return switch (f) {
            case SURNAME -> s.getSurname();
            case FIRST_NAME -> s.getFirstName();
            case OTHER_NAMES -> s.getOtherNames();
            case DATE_OF_BIRTH -> s.getDateOfBirth() == null ? null : s.getDateOfBirth().toString();
            case GENDER -> s.getGender();
        };
    }

    static String display(ProfileRequest.Field f, String v) {
        if (v == null || v.isEmpty()) return "(none)";
        return f == ProfileRequest.Field.GENDER ? ProfileRules.genderLabel(v) : v;
    }

    /** "surname: Okafur → Okafor", "new photo". */
    static String what(ProfileRequest r) {
        return r.getKind() == ProfileRequest.Kind.PHOTO ? "new photo"
                : ProfileRules.fieldLabel(r.getField().name()).toLowerCase(Locale.ROOT) + ": " + display(r.getField(), r.getCurrentValue()) + " → " + display(r.getField(), r.getRequestedValue());
    }

    static ApiException notPending(ProfileRequest r) {
        String msg = switch (r.getStatus()) {
            case APPROVED -> "This request was already approved by " + r.getDecidedByName() + ".";
            case DECLINED -> "This request was already declined by " + r.getDecidedByName() + ".";
            case WITHDRAWN -> "This request was withdrawn.";
            default -> "This request is no longer waiting.";
        };
        return new ApiException(HttpStatus.CONFLICT, "not_pending", msg);
    }

    private void received(Student s, String what) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("firstName", s.getFirstName());
        v.put("what", what);
        history.send(s, "student.request_received", v);
    }

    private ProfileRequest own(Student s, UUID id) {
        return tx.execute(t -> requests.findInTenant(id).filter(r -> r.getStudentId().equals(s.getId())).orElseThrow(ApiException::notFound));
    }

    private ProfileRequest base(Student s, ProfileRequest.Kind kind) {
        ProfileRequest r = new ProfileRequest();
        r.setId(UUID.randomUUID());
        r.setStudentId(s.getId());
        r.setKind(kind);
        r.setStatus(ProfileRequest.Status.PENDING);
        r.setSubmittedAt(clock.instant());
        return r;
    }

    private Student mine(CurrentUser cu) {
        if (cu.activeRole() != Role.STUDENT) throw ApiException.forbidden();
        return tx.execute(t -> students.findByUserId(cu.userId()).orElseThrow(ApiException::notFound));
    }

    private static AuditService.Actor studentActor(Student s, CurrentUser cu) {
        return new AuditService.Actor(cu.userId(), cu.tenantId(), s.displayName(), Role.STUDENT.name());
    }

    private static String prefix(Student s) {
        return "profile/" + TenantContext.require() + "/" + s.getId() + "/";
    }

    static String ext(String name) {
        return name != null && name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
