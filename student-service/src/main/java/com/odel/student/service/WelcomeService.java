package com.odel.student.service;

import com.odel.common.ApiException;
import com.odel.common.PageResponse;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.student.client.NotificationClient;
import com.odel.student.client.TenantClient;
import com.odel.student.dto.AcademicLookup;
import com.odel.student.dto.NotificationDtos;
import com.odel.student.dto.WelcomeDtos.*;
import com.odel.student.entity.Student;
import com.odel.student.entity.WelcomeSend;
import com.odel.student.repository.StudentRepository;
import com.odel.student.repository.WelcomeRepository;
import com.odel.student.repository.WelcomeSendRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * W-20 welcome emails: the template (owned here, one per university), a test to the admin, the send to every student not
 * yet active as a background job (WelcomeWorker), and the delivery report. Emails go out through notification-service.
 */
@Service
public class WelcomeService {
    public static final String TEMPLATE_KEY = "student.welcome";

    private final WelcomeRepository repo;
    private final WelcomeSendRepository sends;
    private final StudentRepository students;
    private final NotificationClient notifications;
    private final TenantClient tenants;
    private final AcademicLookupService academic;
    private final ActorService actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WelcomeService(WelcomeRepository repo, WelcomeSendRepository sends, StudentRepository students, NotificationClient notifications,
                          TenantClient tenants, AcademicLookupService academic, ActorService actors, AuditService audit,
                          TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.sends = sends;
        this.students = students;
        this.notifications = notifications;
        this.tenants = tenants;
        this.academic = academic;
        this.actors = actors;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- template

    public TemplateView template() {
        return repo.template(TenantContext.require())
                .map(t -> new TemplateView(t.subject(), t.body(), true, t.updatedAt(), t.updatedByName()))
                .orElse(new TemplateView(WelcomeTemplates.DEFAULT_SUBJECT, WelcomeTemplates.DEFAULT_BODY, false, null, null));
    }

    public Overview overview() {
        UUID t = TenantContext.require();
        TemplateView template = template();
        return tx.execute(s -> {
            Student sample = sample();
            Map<String, String> values = sample == null ? sampleValues() : values(sample, academic.lookup(), tenants.portalUrl());
            Preview preview = new Preview(sample == null ? "Adeola Okafor" : sample.displayName(),
                    WelcomeTemplates.render(template.subject(), values), WelcomeTemplates.render(template.body(), values));
            long imported = students.countByStatus(Student.Status.IMPORTED), invited = students.countByStatus(Student.Status.INVITED);
            long noEmail = students.count((root, q, cb) -> cb.and(root.get("status").in(Student.Status.IMPORTED, Student.Status.INVITED),
                    cb.isNull(root.get("email"))));
            SendView last = sends.findFirstByKindOrderByStartedAtDesc(WelcomeSend.Kind.ALL).map(this::view).orElse(null);
            return new Overview(template, WelcomeTemplates.FIELDS, preview,
                    new Audience(imported + invited, imported + invited - noEmail, noEmail, students.countByStatus(Student.Status.ACTIVE)), last);
        });
    }

    public Overview saveTemplate(CurrentUser cu, TemplateRequest in) {
        Map<String, String> problems = WelcomeTemplates.check(in.subject(), in.body());
        if (!problems.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", problems.values().iterator().next(), problems);
        AuditService.Actor actor = actors.actor(cu);
        UUID t = TenantContext.require();
        TemplateView before = template();
        tx.executeWithoutResult(s -> {
            repo.saveTemplate(t, in.subject().trim(), in.body().strip(), actor.userId(), actor.name(), clock.instant());
            audit.record(new AuditService.Entry(t, actor, "WELCOME_TEMPLATE_UPDATED", "welcome_template", null, actor.name() + " changed the welcome email",
                    Map.of("subject", before.subject(), "body", before.body()), Map.of("subject", in.subject().trim(), "body", in.body().strip())));
        });
        return overview();
    }

    public Overview resetTemplate(CurrentUser cu) {
        AuditService.Actor actor = actors.actor(cu);
        UUID t = TenantContext.require();
        tx.executeWithoutResult(s -> {
            repo.deleteTemplate(t);
            audit.record(new AuditService.Entry(t, actor, "WELCOME_TEMPLATE_UPDATED", "welcome_template", null,
                    actor.name() + " put the welcome email back to the default", null, null));
        });
        return overview();
    }

    /** "Send a test to me": the admin's own address, filled with a sample student. Unsaved editor text may be sent. */
    public TestResult test(CurrentUser cu, TestRequest in) {
        TemplateView saved = template();
        String subject = in == null || in.subject() == null || in.subject().isBlank() ? saved.subject() : in.subject();
        String body = in == null || in.body() == null || in.body().isBlank() ? saved.body() : in.body();
        Map<String, String> problems = WelcomeTemplates.check(subject, body);
        if (!problems.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", problems.values().iterator().next(), problems);
        var staff = actors.staff(cu);
        if (staff.email() == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "no_email", "Your account has no email address.");
        Student sample = tx.execute(s -> sample());
        Map<String, String> values = sample == null ? sampleValues() : values(sample, academic.lookup(), tenants.portalUrl());
        notifications.send(new NotificationDtos.Message(cu.userId(), staff.email(), TEMPLATE_KEY,
                Map.of("subject", "[Test] " + WelcomeTemplates.render(subject, values), "body", WelcomeTemplates.render(body, values)), null, tenants.look()));
        AuditService.Actor actor = actors.actor(cu);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "WELCOME_TEST_SENT", "welcome_template", null,
                actor.name() + " sent a test welcome email to " + staff.email(), null, null));
        return new TestResult(staff.email());
    }

    // ---------------------------------------------------------------- sends

    /** "Send to N students": every student not yet active; the worker sends them in the background. */
    public SendView startAll(CurrentUser cu, StartRequest in) {
        Integer remind = in == null ? null : in.remindAfterDays();
        if (remind != null && (remind < 1 || remind > 30)) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Remind after 1 to 30 days.", Map.of("remindAfterDays", "Remind after 1 to 30 days."));
        }
        AuditService.Actor actor = actors.actor(cu);
        TemplateView template = template();
        UUID t = TenantContext.require();
        WelcomeSend send = tx.execute(s -> {
            if (sends.findFirstByKindAndStatus(WelcomeSend.Kind.ALL, WelcomeSend.Status.RUNNING).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "send_running", "The welcome emails are still being sent. Wait for them to finish.");
            }
            Instant now = clock.instant();
            WelcomeSend w = newSend(WelcomeSend.Kind.ALL, template.subject(), template.body(), actor, now);
            w.setRemindAfterDays(remind);
            w.setReminderDueAt(remind == null ? null : now.plus(Duration.ofDays(remind)));
            long active = students.countByStatus(Student.Status.ACTIVE);
            w.setSkippedActive((int) active);
            sends.saveAndFlush(w);
            int n = repo.addAllNotActivated(t, w.getId(), now);
            if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "nobody_to_send", "Every student has already activated, or none are imported yet.");
            w.setTotalStudents(n);
            sends.save(w);
            audit.record(new AuditService.Entry(t, actor, "WELCOME_SENT", "welcome_send", w.getId(),
                    actor.name() + " sent the welcome email to " + n + " students not yet active (" + active + " already active skipped)", null,
                    Map.of("students", n, "skippedActive", active, "remindAfterDays", remind == null ? 0 : remind)));
            return w;
        });
        return view(send);
    }

    /** One student's welcome (Add and send welcome, Resend activation); the worker sends it within seconds. */
    public void sendOne(Student student, AuditService.Actor actor) {
        TemplateView template = template();
        Instant now = clock.instant();
        WelcomeSend w = newSend(WelcomeSend.Kind.SINGLE, template.subject(), template.body(), actor, now);
        w.setTotalStudents(1);
        sends.saveAndFlush(w);
        repo.addOne(TenantContext.require(), w.getId(), student.getId(), student.getEmail(), now);
    }

    public SendView send(UUID id) {
        return tx.execute(s -> sends.findById(id).map(this::view).orElseThrow(ApiException::notFound));
    }

    public PageResponse<SendView> sends(int page, int size) {
        return tx.execute(s -> PageResponse.of(sends.findByKindInOrderByStartedAtDesc(List.of(WelcomeSend.Kind.ALL, WelcomeSend.Kind.REMINDER),
                PageRequest.of(page, Math.max(1, Math.min(size, 50)))), this::view));
    }

    public PageResponse<DeliveryView> deliveries(UUID sendId, String status, int page, int size) {
        send(sendId);
        List<String> statuses = statuses(status);
        int sz = Math.max(1, Math.min(size, 200));
        UUID t = TenantContext.require();
        return new PageResponse<>(repo.deliveries(t, sendId, statuses, page * sz, sz), page, sz, repo.countDeliveries(t, sendId, statuses));
    }

    /** "Download the failed list": failed and skipped deliveries, with the reason. */
    public String failedCsv(UUID sendId) {
        send(sendId);
        List<DeliveryView> rows = repo.deliveries(TenantContext.require(), sendId, List.of("FAILED", "SKIPPED_NO_EMAIL", "SKIPPED_TEST_ADDRESS"), 0, 100_000);
        StringBuilder sb = new StringBuilder("matric_number,name,email,result,reason\r\n");
        for (DeliveryView d : rows) {
            sb.append(CsvParser.escape(d.matricNumber())).append(',').append(CsvParser.escape(d.fullName())).append(',')
                    .append(CsvParser.escape(d.email())).append(',').append(label(d.status())).append(',').append(CsvParser.escape(d.reason())).append("\r\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- filling the email (also used by the worker)

    public NotificationDtos.Message message(Student s, String subject, String body, AcademicLookup lookup, String portalUrl,
                                            NotificationDtos.TenantLook look) {
        Map<String, String> values = values(s, lookup, portalUrl);
        values.put("university_name", look.name());
        return new NotificationDtos.Message(s.getUserId(), s.getEmail(), TEMPLATE_KEY,
                Map.of("subject", WelcomeTemplates.render(subject, values), "body", WelcomeTemplates.render(body, values)), "/", look);
    }

    Map<String, String> values(Student s, AcademicLookup lookup, String portalUrl) {
        Map<String, String> v = new HashMap<>();
        v.put("first_name", s.getFirstName());
        v.put("surname", s.getSurname());
        v.put("full_name", s.displayName());
        v.put("matric", s.getMatricNumber());
        v.put("programme", s.getProgrammeName());
        v.put("level", s.getLevel() + "L");
        v.put("centre_name", s.getCentreName());
        String phone = lookup.centres().stream().filter(c -> c.code().equalsIgnoreCase(s.getCentreCode())).map(AcademicLookup.Centre::phone)
                .filter(Objects::nonNull).findFirst().orElse("the number on your admission letter");
        v.put("centre_phone", phone);
        v.put("session", s.getEntrySession() != null ? s.getEntrySession()
                : lookup.currentSession() == null ? "" : lookup.currentSession().name());
        v.put("activation_link", portalUrl + "/activate?matric=" + URLEncoder.encode(s.getMatricNumber(), StandardCharsets.UTF_8));
        v.put("university_name", tenants.tenant().name());
        return v;
    }

    private Map<String, String> sampleValues() {
        Map<String, String> v = WelcomeTemplates.examples();
        v.put("university_name", tenants.tenant().name());
        v.put("activation_link", tenants.portalUrl() + "/activate?matric=WU%2FODL%2F24%2F0153");
        return v;
    }

    private Student sample() {
        return students.findAll((root, q, cb) -> root.get("status").in(Student.Status.IMPORTED, Student.Status.INVITED),
                PageRequest.of(0, 1, org.springframework.data.domain.Sort.by("surname", "firstName"))).stream().findFirst().orElse(null);
    }

    private WelcomeSend newSend(WelcomeSend.Kind kind, String subject, String body, AuditService.Actor actor, Instant now) {
        WelcomeSend w = new WelcomeSend();
        w.setId(UUID.randomUUID());
        w.setKind(kind);
        w.setStatus(WelcomeSend.Status.RUNNING);
        w.setSubject(subject);
        w.setBody(body);
        w.setRequestedBy(actor == null ? null : actor.userId());
        w.setRequestedByName(actor == null ? null : actor.name());
        w.setStartedAt(now);
        return w;
    }

    SendView view(WelcomeSend w) {
        Map<String, Long> c = repo.counts(w.getTenantId(), w.getId());
        long sent = c.getOrDefault("SENT", 0L), failed = c.getOrDefault("FAILED", 0L);
        long sending = c.getOrDefault("PENDING", 0L) + c.getOrDefault("QUEUED", 0L);
        long noEmail = c.getOrDefault("SKIPPED_NO_EMAIL", 0L), test = c.getOrDefault("SKIPPED_TEST_ADDRESS", 0L);
        long all = c.values().stream().mapToLong(Long::longValue).sum();
        long toSend = all - noEmail;
        int pct = toSend == 0 ? 100 : (int) Math.min(100, (sent + failed + test) * 100 / toSend);
        return new SendView(w.getId(), w.getKind().name(), w.getStatus().name(), w.getStartedAt(), w.getFinishedAt(), w.getRemindAfterDays(),
                w.getReminderDueAt(), new Totals(all + w.getSkippedActive(), toSend, sent, failed, sending, noEmail, test, w.getSkippedActive()),
                w.getStatus() == WelcomeSend.Status.DONE ? 100 : pct, w.getRequestedByName());
    }

    private static List<String> statuses(String status) {
        if (status == null || status.isBlank()) return List.of();
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "SENDING" -> List.of("PENDING", "QUEUED");
            case "SENT", "FAILED", "SKIPPED_NO_EMAIL", "SKIPPED_TEST_ADDRESS" -> List.of(status.trim().toUpperCase(Locale.ROOT));
            case "SKIPPED" -> List.of("SKIPPED_NO_EMAIL", "SKIPPED_TEST_ADDRESS");
            default -> throw ApiException.badRequest("invalid", "Status is SENT, FAILED, SKIPPED_NO_EMAIL, SKIPPED_TEST_ADDRESS or SENDING.");
        };
    }

    private static String label(String status) {
        return switch (status) {
            case "FAILED" -> "Failed";
            case "SKIPPED_NO_EMAIL" -> "Not sent: no email";
            case "SKIPPED_TEST_ADDRESS" -> "Not sent: test address";
            default -> status;
        };
    }

    static Set<UUID> ids(Collection<WelcomeRepository.Open> open) {
        return open.stream().map(WelcomeRepository.Open::studentId).collect(Collectors.toSet());
    }
}
