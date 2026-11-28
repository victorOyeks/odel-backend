package com.odel.content.service;

import com.lowagie.text.pdf.PdfReader;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.VideoGateway;
import com.odel.content.config.ContentProperties;
import com.odel.content.entity.Item;
import com.odel.content.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Background work, every 15 seconds: slides and notes to a compressed PDF (LibreOffice headless; kept as the original file when it
 * is not installed or fails), page counts of PDFs, and the audio-only file of each ready video (ffmpeg: the 240p MP4 → 48 kbps mono
 * AAC in storage; skipped when ffmpeg is missing, and students then get the 240p stream with the picture hidden).
 */
@Service
public class ContentJobs {
    private static final Logger log = LoggerFactory.getLogger(ContentJobs.class);
    static final String PDF_OPTIONS = "{\"ReduceImageResolution\":{\"type\":\"boolean\",\"value\":\"true\"},"
            + "\"MaxImageResolution\":{\"type\":\"long\",\"value\":\"150\"},\"Quality\":{\"type\":\"long\",\"value\":\"75\"}}";
    private final ItemRepository items;
    private final FileStore store;
    private final VideoGateway videos;
    private final Lifecycle lifecycle;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ContentProperties props;
    private final Clock clock;

    public ContentJobs(ItemRepository items, FileStore store, VideoGateway videos, Lifecycle lifecycle, JdbcTemplate jdbc, TransactionTemplate tx,
                       ContentProperties props, Clock clock) {
        this.items = items;
        this.store = store;
        this.videos = videos;
        this.lifecycle = lifecycle;
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public record RunResult(int converted, int kept, int pages, int audio) {}

    record Ref(UUID id, UUID tenantId) {}

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT20S")
    public void tick() {
        if (!props.jobsEnabled()) return;
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("Content jobs failed: {}", e.getMessage(), e);
        }
    }

    public synchronized RunResult runOnce() {
        int converted = 0, kept = 0, pages = 0, audio = 0;
        for (Ref r : due("select id, tenant_id from items where status = 'CONVERTING' order by updated_at limit 10")) {
            Boolean ok = TenantContext.runAs(r.tenantId(), () -> convert(r.id()));
            if (Boolean.TRUE.equals(ok)) converted++; else if (ok != null) kept++;
        }
        for (Ref r : due("select id, tenant_id from items where kind in ('PDF', 'SLIDES', 'NOTES') and status = 'READY' and not pages_checked limit 20")) {
            if (TenantContext.runAs(r.tenantId(), () -> countPages(r.id()))) pages++;
        }
        Timestamp retry = Timestamp.from(clock.instant().minus(Duration.ofMinutes(5)));
        for (Ref r : jdbc.query("select id, tenant_id from items where kind = 'VIDEO' and status = 'READY' and audio_status = 'PENDING' "
                        + "and (last_checked_at is null or last_checked_at <= ? or ? ) limit 5",
                (rs, n) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), retry, props.fake())) {
            if (TenantContext.runAs(r.tenantId(), () -> audio(r.id()))) audio++;
        }
        return new RunResult(converted, kept, pages, audio);
    }

    List<Ref> due(String sql) {
        return jdbc.query(sql, (rs, n) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
    }

    // ---- slides and notes → PDF

    /** True = converted, false = kept as the original, null = nothing to do. */
    Boolean convert(UUID id) {
        Item i = tx.execute(s -> items.findInTenant(id).orElse(null));
        if (i == null || i.getStatus() != Item.Status.CONVERTING) return null;
        Path dir = null;
        Path pdf = null;
        String reason = null;
        try {
            dir = Files.createTempDirectory("odel-convert");
            if (!Tools.available(props.sofficePath())) {
                reason = "no converter on this server";
            } else {
                String ext = FileKinds.extension(i.getFileName());
                Path in = dir.resolve("original." + ext);
                store.download(i.getOriginalKey(), in);
                String filter = (i.getKind() == Item.Kind.SLIDES ? "pdf:impress_pdf_Export:" : "pdf:writer_pdf_Export:") + PDF_OPTIONS;
                pdf = soffice(dir, in, filter);
                if (pdf == null) pdf = soffice(dir, in, "pdf");   // older LibreOffice: no export options
                if (pdf == null) reason = "LibreOffice could not convert it";
            }
        } catch (IOException e) {
            reason = e.getMessage();
        }
        Path result = pdf;
        String why = reason;
        try {
            Integer pages = result == null ? null : pages(result);
            long size = result == null ? 0 : Files.size(result);
            String key = result == null ? null : i.getOriginalKey().replaceAll("original\\.[^/]+$", "converted.pdf");
            if (result != null) store.put(key, result, "application/pdf");
            return tx.execute(s -> {
                Item x = items.findInTenant(id).orElse(null);
                if (x == null || x.getStatus() != Item.Status.CONVERTING) return null;
                Instant now = clock.instant();
                if (result != null) {
                    x.setStorageKey(key);
                    x.setSizeBytes(size);
                    x.setContentType("application/pdf");
                    x.setPages(pages);
                    x.setPagesChecked(true);
                    x.setConverted(true);
                    x.setStatus(Item.Status.READY);
                } else {
                    log.info("Kept {} as the original file: {}", x.getFileName(), why);
                    x.setStorageKey(x.getOriginalKey());
                    x.setSizeBytes(x.getOriginalSizeBytes());
                    x.setPagesChecked(true);
                    x.setStatus(Item.Status.KEPT_ORIGINAL);
                }
                x.setReadyAt(now);
                x.setUpdatedAt(now);
                items.flush();
                lifecycle.finished(x);
                return result != null;
            });
        } catch (IOException e) {
            log.warn("Could not store the converted PDF of {}: {}", id, e.getMessage());
            return null;
        } finally {
            deleteQuietly(dir);
        }
    }

    Path soffice(Path dir, Path in, String filter) throws IOException {
        String profile = dir.resolve("profile").toUri().toString();
        Tools.Result r = Tools.run(List.of(props.sofficePath(), "-env:UserInstallation=" + profile, "--headless", "--norestore",
                "--convert-to", filter, "--outdir", dir.toString(), in.toString()), dir, 180);
        Path out = dir.resolve(in.getFileName().toString().replaceAll("\\.[^.]+$", "") + ".pdf");
        if (r.ok() && Files.exists(out) && Files.size(out) > 0) return out;
        log.info("soffice ({}) did not convert: exit {} {}", filter, r.exit(), r.output());
        Files.deleteIfExists(out);
        return null;
    }

    static Integer pages(Path pdf) {
        PdfReader reader = null;
        try {
            reader = new PdfReader(pdf.toString());
            return reader.getNumberOfPages();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) reader.close();
        }
    }

    boolean countPages(UUID id) {
        Item i = tx.execute(s -> items.findInTenant(id).orElse(null));
        if (i == null || i.isPagesChecked() || i.getStorageKey() == null) return false;
        Path dir = null;
        Integer pages = null;
        try {
            dir = Files.createTempDirectory("odel-pages");
            Path f = dir.resolve("file.pdf");
            store.download(i.getStorageKey(), f);
            if ("application/pdf".equals(i.getContentType()) || i.getKind() == Item.Kind.PDF) pages = pages(f);
        } catch (IOException e) {
            log.info("Could not count pages of {}: {}", id, e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
        Integer p = pages;
        tx.executeWithoutResult(s -> items.findInTenant(id).ifPresent(x -> {
            x.setPages(p);
            x.setPagesChecked(true);
        }));
        return p != null;
    }

    // ---- the audio-only file

    boolean audio(UUID id) {
        Item i = tx.execute(s -> items.findInTenant(id).orElse(null));
        if (i == null || i.getAudioStatus() != Item.AudioStatus.PENDING) return false;
        String key = FileStore.key(TenantContext.require(), i.getOfferingId(), i.getId(), "audio-" + i.getVideoId() + ".m4a");
        Path dir = null;
        Item.AudioStatus outcome;
        Long size = null;
        try {
            dir = Files.createTempDirectory("odel-audio");
            Path out = dir.resolve("audio.m4a");
            if (props.fake()) {
                // The fake host has no media: a placeholder file stands in, sized as the real one would be.
                Files.write(out, "fake audio".getBytes());
                store.put(key, out, "audio/mp4");
                size = Views.AUDIO_BYTES_PER_SECOND * (i.getDurationSeconds() == null ? 0 : i.getDurationSeconds());
                outcome = Item.AudioStatus.READY;
            } else if (!Tools.available(props.ffmpegPath())) {
                outcome = Item.AudioStatus.SKIPPED;
            } else {
                Path in = dir.resolve("video.mp4");
                String q = i.qualityMap().containsKey("240p") ? "240p" : "360p";
                if (!videos.downloadMp4(i.getVideoId(), q, in)) {
                    boolean young = i.getReadyAt() != null && i.getReadyAt().isAfter(clock.instant().minus(Duration.ofMinutes(30)));
                    outcome = young ? Item.AudioStatus.PENDING : Item.AudioStatus.FAILED;   // the MP4 may come a little after the stream
                } else {
                    Tools.Result r = Tools.run(List.of(props.ffmpegPath(), "-y", "-loglevel", "error", "-i", in.toString(), "-vn", "-ac", "1",
                            "-c:a", "aac", "-b:a", "48k", "-movflags", "+faststart", out.toString()), dir, 600);
                    if (r.ok() && Files.exists(out) && Files.size(out) > 0) {
                        store.put(key, out, "audio/mp4");
                        size = Files.size(out);
                        outcome = Item.AudioStatus.READY;
                    } else {
                        log.info("ffmpeg could not make the audio of {}: {}", i.getVideoId(), r.output());
                        outcome = Item.AudioStatus.FAILED;
                    }
                }
            }
        } catch (IOException e) {
            log.info("Audio file for {} not made: {}", id, e.getMessage());
            outcome = Item.AudioStatus.FAILED;
        } finally {
            deleteQuietly(dir);
        }
        Item.AudioStatus o = outcome;
        Long s2 = size;
        tx.executeWithoutResult(s -> items.findInTenant(id).ifPresent(x -> {
            x.setLastCheckedAt(clock.instant());
            if (x.getAudioStatus() != Item.AudioStatus.PENDING || !Objects.equals(x.getVideoId(), i.getVideoId())) return;
            x.setAudioStatus(o);
            if (o == Item.AudioStatus.READY) {
                x.setAudioKey(key);
                x.setAudioSizeBytes(s2);
            }
        }));
        return o == Item.AudioStatus.READY;
    }

    static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp files: the OS clears them
        }
    }
}
