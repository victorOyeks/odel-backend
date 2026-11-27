package com.odel.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.OdelProperties;
import com.odel.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs queued jobs when odel.jobs.worker-enabled is true (the worker process; dev runs it in the API).
 * Claims rows with FOR UPDATE SKIP LOCKED so several workers never take the same job. Failures retry
 * with backoff (1, 2, 4, 8 minutes) and then stay FAILED for someone to look at.
 */
@Component
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final Duration STUCK = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Map<String, JobHandler> handlers;
    private final boolean enabled;
    private final Clock clock;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public JobWorker(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, List<JobHandler> handlers,
                     OdelProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.enabled = props.jobs().workerEnabled();
        this.clock = clock;
    }

    record Claimed(UUID id, UUID tenantId, String type, String payload, int attempts, int maxAttempts) {}

    @Scheduled(fixedDelayString = "${odel.jobs.poll-interval:PT1S}")
    public void poll() {
        if (!enabled) return;
        runDue(20);
    }

    /** Runs up to max due jobs now. Public for tests. */
    public int runDue(int max) {
        int done = 0;
        while (done < max) {
            Claimed job = claim();
            if (job == null) break;
            run(job);
            done++;
        }
        return done;
    }

    private Claimed claim() {
        return tx.execute(s -> {
            Instant now = clock.instant();
            List<Claimed> rows = jdbc.query("""
                    select id, tenant_id, type, payload::text, attempts, max_attempts from jobs
                    where (status = 'QUEUED' and run_at <= ?) or (status = 'RUNNING' and locked_at < ?)
                    order by run_at limit 1 for update skip locked""",
                    (rs, i) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getInt(6)),
                    Timestamp.from(now), Timestamp.from(now.minus(STUCK)));
            if (rows.isEmpty()) return null;
            Claimed c = rows.get(0);
            jdbc.update("update jobs set status = 'RUNNING', locked_at = ?, locked_by = ?, attempts = attempts + 1 where id = ?",
                    Timestamp.from(now), workerId, c.id());
            return c;
        });
    }

    private void run(Claimed job) {
        JobHandler handler = handlers.get(job.type());
        try {
            if (handler == null) throw new IllegalStateException("No handler for job type " + job.type());
            JsonNode payload = json.readTree(job.payload());
            TenantContext.runAs(job.tenantId(), () -> {
                try {
                    handler.handle(payload);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            jdbc.update("update jobs set status = 'DONE', finished_at = ?, last_error = null where id = ?", Timestamp.from(clock.instant()), job.id());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            int attempts = job.attempts() + 1;
            boolean give_up = attempts >= job.maxAttempts();
            Instant retryAt = clock.instant().plus(Duration.ofMinutes(1L << Math.min(attempts - 1, 6)));
            log.warn("Job {} {} failed (attempt {}): {}", job.type(), job.id(), attempts, cause.getMessage());
            jdbc.update("update jobs set status = ?, run_at = ?, last_error = ?, finished_at = ? where id = ?",
                    give_up ? "FAILED" : "QUEUED", Timestamp.from(retryAt), String.valueOf(cause.getMessage()),
                    give_up ? Timestamp.from(clock.instant()) : null, job.id());
        }
    }
}
