package com.odel.content.controller;

import com.odel.common.security.CurrentUserArg;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.service.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The staff side (T-01 … T-08): courses, weeks, items and uploads, copy, class progress, announcements. */
@RestController
@RequestMapping("/api/v1/content")
public class StaffContentController {
    private final StaffCourseService courses;
    private final BuilderService builder;
    private final UploadService uploads;
    private final StaffProgressService progress;
    private final AnnouncementService announcements;

    public StaffContentController(StaffCourseService courses, BuilderService builder, UploadService uploads, StaffProgressService progress,
                                  AnnouncementService announcements) {
        this.courses = courses;
        this.builder = builder;
        this.uploads = uploads;
        this.progress = progress;
        this.announcements = announcements;
    }

    // ---- courses (T-01, T-02, T-08)
    @GetMapping("/courses")
    public CourseList courses(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId) {
        return courses.list(cu.user(), semesterId);
    }

    @GetMapping("/offerings/{id}")
    public Overview overview(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return courses.overview(cu.user(), id);
    }

    @PutMapping("/offerings/{id}")
    public Overview settings(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody SettingsIn body) {
        return courses.settings(cu.user(), id, body);
    }

    // ---- weeks (T-03, T-03d, T-03g)
    @GetMapping("/offerings/{id}/weeks")
    public WeekList weeks(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return builder.weeks(cu.user(), id);
    }

    @PostMapping("/offerings/{id}/weeks")
    public ResponseEntity<WeekView> addWeek(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody WeekIn body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(builder.addWeek(cu.user(), id, body));
    }

    @PostMapping("/offerings/{id}/weeks/dates")
    public DatesResult dates(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody DatesIn body) {
        return courses.dates(cu.user(), id, body);
    }

    @GetMapping("/weeks/{id}")
    public WeekView week(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return builder.getWeek(cu.user(), id);
    }

    @PutMapping("/weeks/{id}")
    public WeekView updateWeek(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody WeekIn body) {
        return builder.updateWeek(cu.user(), id, body);
    }

    @DeleteMapping("/weeks/{id}")
    public ResponseEntity<Void> deleteWeek(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        builder.deleteWeek(cu.user(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/weeks/{id}/order")
    public WeekView reorder(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody OrderIn body) {
        return builder.reorder(cu.user(), id, body);
    }

    // ---- items (T-03b/c/e/f/k/l/m, T-04)
    @PostMapping("/weeks/{id}/items")
    public ResponseEntity<StaffItem> addItem(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody ItemIn body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(builder.addItem(cu.user(), id, body));
    }

    @PostMapping("/weeks/{id}/uploads")
    public ResponseEntity<UploadStarted> upload(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody UploadIn body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uploads.start(cu.user(), id, body));
    }

    @PostMapping("/items/{id}/uploaded")
    public StaffItem uploaded(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return uploads.uploaded(cu.user(), id);
    }

    @PostMapping("/items/{id}/upload-again")
    public UploadStarted uploadAgain(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return uploads.again(cu.user(), id);
    }

    @PostMapping("/items/{id}/replace")
    public UploadStarted replace(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody UploadIn body) {
        return uploads.replace(cu.user(), id, body);
    }

    @PutMapping("/items/{id}")
    public StaffItem update(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody ItemIn body) {
        return builder.update(cu.user(), id, body);
    }

    @PostMapping("/items/{id}/hide")
    public StaffItem hide(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return builder.hide(cu.user(), id, true);
    }

    @PostMapping("/items/{id}/show")
    public StaffItem show(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return builder.hide(cu.user(), id, false);
    }

    @PostMapping("/items/{id}/move")
    public StaffItem move(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody MoveIn body) {
        return builder.move(cu.user(), id, body);
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> delete(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        builder.delete(cu.user(), id);
        return ResponseEntity.noContent().build();
    }

    // ---- copy (T-06)
    @GetMapping("/offerings/{id}/copy-sources")
    public CopySources copySources(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return courses.copySources(cu.user(), id);
    }

    @PostMapping("/offerings/{id}/copy")
    public CopyResult copy(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) CopyIn body) {
        return courses.copy(cu.user(), id, body);
    }

    // ---- class progress (T-07, T-07b)
    @GetMapping("/offerings/{id}/progress")
    public ProgressTable progress(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestParam(required = false) String filter,
                                  @RequestParam(required = false) String q, @RequestParam(required = false) UUID centreId) {
        return progress.table(cu.user(), id, filter, q, centreId);
    }

    @GetMapping("/offerings/{id}/progress/{studentId}")
    public ProgressDetail progressDetail(CurrentUserArg.Holder cu, @PathVariable UUID id, @PathVariable UUID studentId) {
        return progress.detail(cu.user(), id, studentId);
    }

    @GetMapping("/offerings/{id}/progress.csv")
    public ResponseEntity<byte[]> progressCsv(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"progress.csv\"")
                .body(progress.csv(cu.user(), id).getBytes(StandardCharsets.UTF_8));
    }

    // ---- announcements (T-05, T-05b)
    @GetMapping("/offerings/{id}/announcements")
    public AnnouncementList announcements(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return announcements.list(cu.user(), id);
    }

    @PostMapping("/offerings/{id}/announcements")
    public ResponseEntity<AnnouncementView> post(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody AnnouncementIn body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(announcements.post(cu.user(), id, body));
    }

    @PutMapping("/announcements/{id}")
    public AnnouncementView edit(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody AnnouncementIn body) {
        return announcements.edit(cu.user(), id, body);
    }
}
