package com.odel.student.service;

import com.odel.common.tenancy.TenantContext;
import com.odel.student.client.NotificationClient;
import com.odel.student.client.TenantClient;
import com.odel.student.dto.AcademicLookup;
import com.odel.student.dto.NotificationDtos;
import com.odel.student.entity.Student;
import com.odel.student.entity.WelcomeSend;
import com.odel.student.repository.StudentRepository;
import com.odel.student.repository.WelcomeRepository;
import com.odel.student.repository.WelcomeSendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sends welcome emails in the background, every two seconds: hands up to 500 waiting deliveries to notification-service
 * (one bulk call per university), then reads back what happened to the ones handed over (sent, failed, test address),
 * closes finished sends and starts due reminders. Rows are claimed with SKIP LOCKED, so two instances never send twice.
 */
@Component
public class WelcomeWorker {
    private static final Logger log = LoggerFactory.getLogger(WelcomeWorker.class);
    private static final int BATCH = 500;

    private final WelcomeRepository repo;
    private final WelcomeSendRepository sends;
    private final StudentRepository students;
    private final WelcomeService welcome;
    private final NotificationClient notifications;
    private final TenantClient tenants;
    private final AcademicLookupService academic;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;

    public WelcomeWorker(WelcomeRepository repo, WelcomeSendRepository sends, StudentRepository students, WelcomeService welcome,
                         NotificationClient notifications, TenantClient tenants, AcademicLookupService academic, TransactionTemplate tx,
                         Clock clock, @Value("${odel.student.welcome-worker:true}") boolean enabled) {
        this.repo = repo;
        this.sends = sends;
        this.students = students;
        this.welcome = welcome;
        this.notifications = notifications;
        this.tenants = tenants;
        this.academic = academic;
        this.tx = tx;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "PT2S", initialDelayString = "PT5S")
    public void tick() {
        if (!enabled) return;
        try {
            for (int i = 0; i < 10 && dispatch() > 0; i++) { /* keep going while there is work, up to 5,000 a tick */ }
            sync();
            tx.executeWithoutResult(s -> repo.finishSends(clock.instant()));
            reminders();
        } catch (Exception e) {
            log.warn("Welcome worker: {}", e.getMessage());
        }
    }

    /** PENDING → QUEUED: render each student's email and hand the batch to notification-service. */
    int dispatch() {
        Integer n = tx.execute(s -> {
            List<WelcomeRepository.Open> open = repo.lockPending(BATCH);
            Map<UUID, List<WelcomeRepository.Open>> byTenant = open.stream().collect(Collectors.groupingBy(WelcomeRepository.Open::tenantId));
            Instant now = clock.instant();
            byTenant.forEach((tenant, list) -> TenantContext.runAs(tenant, () -> {
                Map<UUID, Student> found = repo.students(tenant, list.stream().map(WelcomeRepository.Open::studentId).toList()).stream()
                        .collect(Collectors.toMap(Student::getId, x -> x));
                Map<UUID, WelcomeRepository.SendText> sendRows = repo.sendTexts(tenant, list.stream().map(WelcomeRepository.Open::sendId).distinct().toList());
                AcademicLookup lookup = academic.lookup();
                String portal = tenants.portalUrl();
                NotificationDtos.TenantLook look = tenants.look();
                List<WelcomeRepository.Open> going = new ArrayList<>();
                List<NotificationDtos.Message> messages = new ArrayList<>();
                List<Object[]> updates = new ArrayList<>();
                for (WelcomeRepository.Open d : list) {
                    Student st = found.get(d.studentId());
                    WelcomeRepository.SendText send = sendRows.get(d.sendId());
                    if (st == null || send == null || st.getEmail() == null) {
                        updates.add(new Object[]{"SKIPPED_NO_EMAIL", null, "No email on record", Timestamp.from(now), d.id()});
                        continue;
                    }
                    if (st.getStatus() == Student.Status.ACTIVE || st.getStatus() == Student.Status.SUSPENDED) {
                        updates.add(new Object[]{"FAILED", null, st.getStatus() == Student.Status.ACTIVE ? "Already active" : "Suspended", Timestamp.from(now), d.id()});
                        continue;
                    }
                    String subject = "REMINDER".equals(send.kind()) ? "Reminder: " + send.subject() : send.subject();
                    messages.add(welcome.message(st, subject, send.body(), lookup, portal, look));
                    going.add(d);
                }
                List<UUID> ids = notifications.sendAll(messages);
                List<UUID> invited = new ArrayList<>();
                for (int i = 0; i < going.size(); i++) {
                    UUID id = i < ids.size() ? ids.get(i) : null;
                    updates.add(id == null ? new Object[]{"FAILED", null, "No email template", Timestamp.from(now), going.get(i).id()}
                            : new Object[]{"QUEUED", id, null, Timestamp.from(now), going.get(i).id()});
                    if (id != null) invited.add(going.get(i).studentId());
                }
                repo.setStatus(updates);
                repo.markInvited(tenant, invited, now);
            }));
            return open.size();
        });
        return n == null ? 0 : n;
    }

    /** QUEUED → SENT / FAILED / SKIPPED_TEST_ADDRESS, from notification-service. */
    void sync() {
        tx.executeWithoutResult(s -> {
            List<WelcomeRepository.Open> open = repo.lockQueued(2000);
            Instant now = clock.instant();
            open.stream().collect(Collectors.groupingBy(WelcomeRepository.Open::tenantId)).forEach((tenant, list) -> TenantContext.runAs(tenant, () -> {
                Map<UUID, WelcomeRepository.Open> byMessage = list.stream().filter(d -> d.messageId() != null)
                        .collect(Collectors.toMap(WelcomeRepository.Open::messageId, d -> d, (a, b) -> a));
                List<Object[]> updates = new ArrayList<>();
                for (var st : notifications.status(new ArrayList<>(byMessage.keySet()))) {
                    WelcomeRepository.Open d = byMessage.get(st.id());
                    if (d == null) continue;
                    switch (st.status()) {
                        case "SENT" -> updates.add(new Object[]{"SENT", null, null, Timestamp.from(now), d.id()});
                        case "FAILED" -> updates.add(new Object[]{"FAILED", null, st.error() == null ? "Could not be delivered" : cut(st.error()), Timestamp.from(now), d.id()});
                        case "SKIPPED" -> updates.add(new Object[]{"SKIPPED_TEST_ADDRESS", null, st.error() == null ? "Not sent: test address" : cut(st.error()), Timestamp.from(now), d.id()});
                        default -> { }
                    }
                }
                repo.setStatus(updates);
            }));
        });
    }

    /** "Remind anyone who has not activated after 3 days": one more email, once. */
    void reminders() {
        tx.executeWithoutResult(s -> {
            Instant now = clock.instant();
            for (WelcomeRepository.Due due : repo.remindersDue(now)) repo.startReminder(due.tenantId(), due.id(), now);
        });
    }

    private static String cut(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
