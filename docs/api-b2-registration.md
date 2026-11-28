# API contract · B2 Course registration (registration-service) and the centre move (A-06)

B2 is split in two halves. This file is the **course registration** half: the registration window, the courses a student
registers each semester (compulsory ticked and locked, electives to choose, carry-overs pinned), unit limits, the fee block, the
PDF slip, late registration, course changes, the coordinator's approvals, the registrations report (A-07), the rules (A-05), and
moving a student to another centre (A-06, student-service). The payments half (payment-service, :9306) has its own file.

Same conventions as `docs/api-b0.md`: paths start with `/api/v1`, `X-Odel-Host` on every call, CSRF header on every non-GET,
errors `{"error","message","fields"}`, lists `{"items","page","size","total"}`, dates `YYYY-MM-DD` (calendar days in Africa/Lagos),
times ISO-8601. Ids from another university → `404`.

## Services and routes

| Service | Port | Schema | Gateway prefixes |
|---|---|---|---|
| `registration-service` (new) | 9307 | `registration` | `/api/v1/registration/**`, `/api/v1/public/registration/**`, `/api/v1/dev/registration/**` (dev profile only), `/api-docs/registration` |
| `student-service` | 9305 | `student` | adds `POST /students/{id}/move-centre`, `GET /students/{id}/centre-moves`, `POST /students/{id}/centre-moves/{moveId}/cancel` (already under `/api/v1/students/**`) |
| `academic-service` | 9304 | `academic` | no new public routes; new internal endpoints (below) |
| `notification-service` | 9303 | `notification` | changelog `005-registration-templates.sql` (new templates below) |

`/api/v1/public/registration/**` is routed to registration-service **before** tenant-service's `/api/v1/public/**`.

**Who may call:** `/registration/me/**` needs the active role `STUDENT`. Staff endpoints: `ODEL_ADMIN` (everything),
`ODEL_DIRECTOR` (read only), `CENTRE_COORDINATOR` (the approvals queue, decisions and the report, **for the centres they coordinate in
the current session only**). Anyone else → `403 forbidden`.

---

## 1. The semester, the window and the fee (student)

The semester a student registers for is worked out from academic-service: the **active** session's semesters that have a
registration window; the first one whose course-change deadline has not passed, otherwise the last one. Every call below accepts
`?semesterId=` to pick another semester of the active session.

Window states (today in Africa/Lagos, all dates inclusive):

| `state` | When | What the student can do |
|---|---|---|
| `NOT_SET` | no active session, or the semester has no registration window | nothing ("Registration for this semester is not set up yet. Check back later.") |
| `NOT_OPEN` (R-01) | before `opensOn` | nothing; "Registration opens Tue 2 Feb 2027." |
| `OPEN` | `opensOn` … `closesOn` | register |
| `LATE` | after `closesOn`, until `lateClosesOn` | ask for late registration (R-06); register once approved |
| `CLOSED` | after `lateClosesOn` (or after `closesOn` when there is no late close) | nothing; course changes until `changesCloseOn` |

`changesCloseOn` (course changes after submitting, R-09) comes from the rules (A-05); by default the day after the late close
(or after the close). An approved late request lets the student register until its `registerBy` date.

### `GET /registration/me` (Home panel, R-01, R-08, R-05b)
```json
{"student":{"id","matricNumber":"WU/ODL/24/0153","fullName":"Adeola Okafor","programme":{"id","code","name"},"level":200,"levelLabel":"200L",
            "centre":{"id","code","name"}},
 "semester":{"id","sessionId","sessionName":"2026/2027","number":1,"label":"2026/2027 · 1st semester","name":"First semester"},
 "window":{"state":"OPEN","opensOn":"2027-02-02","closesOn":"2027-02-16","lateClosesOn":"2027-02-26","changesCloseOn":"2027-02-27",
           "label":"Open until Tue 16 Feb 2027","registerBy":"2027-02-16"},
 "fee":{"required":true,"status":"PAID","paid":true,"label":"Platform fee · 2026/2027 · 1st semester","payPath":"/payments"},
 "canRegister":true,
 "blocked":null,
 "registration":null,
 "lateRequest":null,
 "changeRequest":null,
 "limits":{"minUnits":15,"maxUnits":20,"maxElectives":3}}
```
- `blocked` explains why `canRegister` is false: `{"code","message"}` with `code` one of
  `not_set`, `not_open`, `fee_not_paid` (R-08: "Pay the ₦15,000 platform fee for 2026/2027 · 1st semester first. Registration opens as soon as it is confirmed."),
  `fee_confirming` ("We are confirming your payment. Do not pay again: registration opens as soon as it is confirmed."),
  `late_request_needed` ("Registration closed on Tue 16 Feb. Ask your centre for late registration by Fri 26 Feb."),
  `late_request_pending`, `closed`, `already_registered`, `suspended`.
- `fee.status`: `PAID`, `NOT_PAID`, `CONFIRMING`, `NOT_REQUIRED` (the university switched the fee block off), `UNKNOWN`
  (payment-service could not be reached: the student is told to try again in a minute; registration stays blocked).
- `registration`: the summary of the student's registration for the semester (as in section 3, without `history`), or null.
- `lateRequest` / `changeRequest`: the newest request of that kind for the semester (section 4), or null.

### `GET /registration/me/courses?semesterId=` (R-02, R-02b, R-09b)
```json
{"semester":{…},"window":{…},"limits":{"minUnits":15,"maxUnits":20,"maxElectives":3},
 "compulsory":[{"courseId","code":"CSC 201","title":"Data Structures and Algorithms","units":3,"kind":"COMPULSORY","locked":true,"selected":true}],
 "carryOvers":[{"courseId","code":"CSC 103","title":"Introduction to Programming","units":3,"kind":"CARRY_OVER","locked":true,"selected":true,"fromSession":"2025/2026"}],
 "electives":[{"courseId","code":"CSC 209","title":"Web Technologies","units":2,"kind":"ELECTIVE","locked":false,"selected":false}],
 "lockedUnits":13,"lockedCourses":5,
 "subtitle":"B.Sc. Computer Science · 200L · closes Tue 16 Feb"}
```
Compulsory = the courses academic-service offers to the student's programme as `COMPULSORY` at the student's level in this
semester; electives = the `ELECTIVE` ones. Carry-overs = the student's open carry-overs (section 7) whose course runs in this
semester, pinned (always counted, never removable; "Carry-over courses always count towards the maximum"). When the student is
already registered, `selected` reflects the registration (the R-09b change picker).

### `POST /registration/me/check` `{"semesterId":null,"electiveIds":["uuid"]}` (live counter, R-03, R-03b)
Checks a choice without saving:
```json
{"totalUnits":18,"courseCount":7,"electiveCount":2,"ok":true,"problems":[],
 "counter":"18 · min 15 · max 20"}
```
`problems` (each `{"code","message"}`), the same ones submit refuses with:
- `over_max` (R-03): "That is 21 units. The most you can take is 20: remove an elective."
- `under_min` (R-03b): "That is 13 units, 2 under the minimum of 15. Add an elective."
- `too_many_electives`: "Choose up to 3 electives. You picked 4."
- `not_an_elective`: "MTH 101 is not an elective for your programme this semester."
- `rules_conflict`: "Your compulsory and carry-over courses come to 23 units, over the maximum of 20. Contact your study centre: the ODeL office needs to adjust the unit limits."

### `POST /registration/me/submit` `{"semesterId":null,"electiveIds":["uuid","uuid"],"confirm":true}` (R-04 → R-05b)
`201` → the registration (section 3). The registration is final: later changes only through a course change request.
Errors: `400 confirm_required` ("Tick the box to confirm these are your courses."), `400 invalid` with `problems` in the body (the
first problem's message is `message`), `409 not_open` / `closed` / `late_request_needed` / `not_set`, `402 fee_not_paid` (R-08,
body also has `"payPath":"/payments"`), `409 fee_confirming`, `503 fee_unknown`, `409 already_registered` ("You have already
registered for this semester. Ask for a course change instead."), `403 suspended`.
Sends the student the email and bell item `registration.confirmed` (with the slip link).

---

## 2. Slip (R-05)

### `GET /registration/me/registrations/{id}/slip.pdf` · staff: `GET /registration/registrations/{id}/slip.pdf`
`application/pdf`, `Content-Disposition: attachment; filename="REG-26-1-0153.pdf"`. A4: the university's crest (its logo; the
initials in its colour when it has none), name and "Directorate of Distance & E-learning", "Course registration slip · 2026/2027
first semester", student, matric no., programme · level, study centre, slip no., submitted (and "Updated 20 Feb 2027" after an
approved change), the courses (code, title, units, type Core / Elective / Carry-over) with the total, and the footer
"Verify this slip at {portal}/verify · code R7K2-9X1M · generated 4 Feb 2027".

Slip number `REG-<yy>-<semester>-<the last group of the matric number>` (`REG-26-1-0153`; `-2`, `-3` … when another student
already has it). An approved change keeps the number and raises `slipVersion`.

### `GET /public/registration/verify/{code}` (no sign-in; the university from the host)
`{"valid":true,"slipNumber":"REG-26-1-0153","studentName":"Adeola Okafor","matricNumber":"WU/ODL/24/0153","programme":"B.Sc. Computer Science · 200L",
"semester":"2026/2027 · 1st semester","courseCount":7,"totalUnits":18,"submittedAt","updatedAt","slipVersion":1}` or `404 not_found`
("We could not find a slip with this code. Check the code on the slip.").

---

## 3. The registration

```json
{"id","semester":{…},"status":"REGISTERED","late":false,
 "slipNumber":"REG-26-1-0153","slipVersion":1,"verificationCode":"R7K2-9X1M",
 "courses":[{"courseId","code":"CSC 103","title":"…","units":3,"kind":"CARRY_OVER","kindLabel":"Carry-over"}],
 "courseCount":7,"totalUnits":18,"submittedAt","updatedAt",
 "changesCloseOn":"2027-02-27","canRequestChange":true,
 "centre":{"id","code","name"},
 "history":[{"at","action":"SUBMITTED","summary":"Registered 7 courses, 18 units","by":"Adeola Okafor","slipVersion":1},
            {"at","action":"CHANGE_APPLIED","summary":"Dropped MTH 211, added CSC 211 (approved by Mrs Bisi Ade)","by":"Mrs Bisi Ade","slipVersion":2}]}
```
- `GET /registration/me/registrations` → `{"items":[<registration without history>]}` (newest first).
- `GET /registration/me/registrations/{id}` → the registration with `history`.

---

## 4. Late registration and course changes (student R-06 … R-09e)

A request:
```json
{"id","kind":"LATE|CHANGE","status":"PENDING|APPROVED|DECLINED|CANCELLED","statusLabel":"Waiting for a decision",
 "semester":{…},"student":{"id","matricNumber","fullName","programme":"B.Sc. Computer Science","level":200,"levelLabel":"200L"},
 "centre":{"id","name","hasCoordinator":true},
 "reasonCategory":"MEDICAL","reasonCategoryLabel":"Medical","reason":"I was admitted to hospital from 10 to 18 Feb. My discharge letter is attached.",
 "attachment":{"fileName":"discharge-letter.jpg","size":245760,"sizeLabel":"240 KB","contentType":"image/jpeg","type":"JPG"}|null,
 "change":{"drop":[{"courseId","code":"MTH 211","title":"Linear Algebra I","units":3}],"add":[{…CSC 211…}],"unitsBefore":18,"unitsAfter":18,
           "maxUnits":20,"summary":"Drop MTH 211, add CSC 211"}|null,
 "registerBy":"2027-02-26"|null,
 "decision":{"at","by":"Mrs Bisi Ade","role":"CENTRE_COORDINATOR","note":"…"}|null,
 "createdAt","registeredOn":"2027-02-04"|null}
```
`reasonCategory`: `MEDICAL`, `BEREAVEMENT`, `WORK`, `TRAVEL`, `FEES`, `TECHNICAL`, `OTHER`.

### Late registration
1. `POST /registration/me/uploads` `{"fileName":"discharge-letter.jpg","contentType":"image/jpeg","size":245760}` →
   `{"key","uploadUrl","method":"PUT","headers":{"Content-Type":"image/jpeg"},"expiresAt"}`. JPG, PNG or PDF, at most 5 MB
   (`400 invalid` with `fields.fileName` / `fields.size`). The browser PUTs the file there; the form then shows the attached file
   from what it sent (C30: name, size, type) and may replace or remove it before sending.
2. `POST /registration/me/late-requests` `{"semesterId":null,"reasonCategory":"MEDICAL","reason":"…","attachment":{"key","fileName","contentType","size"}}`
   → `201` the request. Only while the window is `LATE`, not yet registered, with no pending or approved late request.
   Errors: `409 not_late` ("Registration is open: register now." / "Registration has not opened yet." / "Late registration closed on
   Fri 26 Feb. Contact your study centre."), `409 already_registered`, `409 request_pending` ("You already asked; the decision will
   come by email."), `400 invalid` (`fields.reason` 10–1000 characters, `fields.reasonCategory`, `fields.attachment` "Upload the file
   again." when the key is not this student's upload or is missing from storage). The coordinator of the student's centre is told
   (`registration.request_new`; when the centre has no coordinator the request waits in the ODeL office queue, A-03e).
3. Approved → the student gets `registration.late_approved` ("approved by Mrs Bisi Ade on 20 Feb. Register by Fri 26 Feb") and
   `GET /registration/me` shows `canRegister:true`, `window.registerBy` = the request's `registerBy` (the late close date, or three days
   after the decision if it came after the late close). Declined → `registration.late_declined` with the note; she may ask again.

### Course change (R-09 … R-09e)
`POST /registration/me/change-requests` `{"registrationId","dropCourseIds":["uuid"],"addCourseIds":["uuid"],"reason":"MTH 211 clashes with my work shifts; CSC 211 has weekend classes."}`
→ `201` the request (R-09c). Rules: only electives can be dropped ("CSC 201 is compulsory, so it cannot be dropped." / carry-overs
likewise); only electives offered this semester can be added; the result must keep the unit limits and the electives limit (same
problems as `check`); at least one course dropped or added; reason 10–1000 characters; until `changesCloseOn`
(`409 changes_closed` "Course changes closed on Sat 27 Feb."); one pending change at a time (`409 request_pending`).

- `GET /registration/me/requests?semesterId=` → `{"items":[<request>]}` newest first (both kinds).
- `GET /registration/me/requests/{id}` → the request. `GET /registration/me/requests/{id}/attachment` → `{"url","expiresAt"}` (signed link, 5 minutes).
- `POST /registration/me/requests/{id}/cancel` → the request (`CANCELLED`); only while `PENDING` (`409 not_pending`).

Approved change → applied at once to the registration (dropped courses removed, added ones added, `slipVersion` +1, a history
entry), the student gets `registration.change_approved` with the new slip link (R-09d). Declined → `registration.change_declined`
with the note (R-09e).

---

## 5. Approvals (staff: A-03 … A-03e)

### `GET /registration/requests?kind=LATE|CHANGE&status=PENDING|APPROVED|DECLINED|CANCELLED|ALL&centreId=&noCoordinator=true&semesterId=&page=0&size=25`
Default `status=PENDING`, newest first. A coordinator sees only requests from the centres they coordinate in the current session
(another centre's `centreId` → an empty list). The ODeL admin and director see every centre; `noCoordinator=true` is the ODeL office
queue (A-03e: centres with no coordinator in the current session).
```json
{"items":[<request>],"page":0,"size":25,"total":2,"counts":{"late":1,"change":1}}
```
### `GET /registration/requests/{id}` → the request plus `"registration":<the student's registration or null>` (A-03c shows the
current courses). A coordinator asking for another centre's request → `404`.
### `GET /registration/requests/{id}/attachment` → `{"url","expiresAt"}` (A-03f file preview).
### `POST /registration/requests/{id}/approve` `{"note":null}` → the request.
### `POST /registration/requests/{id}/decline` `{"note":"The discharge letter photo is not readable. Please upload a clearer photo or PDF."}` → the request (note required, 5–500 characters).
Who decides: the coordinator of the request's centre (current session) or an ODeL admin. Anyone else → `403 forbidden`; another
centre's request → `404`. Not pending → `409 not_pending` ("This request was already approved by Mrs Bisi Ade."). Approving a
change whose result no longer fits the rules (the registration changed meanwhile) → `409 no_longer_fits` with the problem.
Audited: `REGISTRATION_LATE_APPROVED`, `REGISTRATION_LATE_DECLINED`, `REGISTRATION_CHANGE_APPROVED`, `REGISTRATION_CHANGE_DECLINED`.

---

## 6. Registrations report (A-07, A-07b) and rules (A-05 … A-05c)

### `GET /registration/report?semesterId=&centreId=&programmeId=&level=&status=&q=&page=0&size=25`
Every student of the university (or of the coordinator's centres) with their registration state for the semester:
```json
{"semester":{…},"window":{…},"subtitle":"2026/2027 · 1st semester · open 2–16 Feb 2027 · late registration until 26 Feb",
 "cards":{"registered":2846,"paid":3012,"paidNotRegistered":166,"lateRequests":5,"lateRequestsAtCentres":3,"lateRequestsAtOffice":2,"courseChanges":1},
 "items":[{"studentId","fullName":"Adeola Okafor","matricNumber":"WU/ODL/24/0153","programme":{"id","code","name"},"level":200,
           "centre":{"id","code","name"},"courseCount":7,"units":18,"submittedAt","changedAt":"…"|null,"registrationId",
           "status":"REGISTERED","statusLabel":"Registered","feeStatus":"PAID"}],
 "page":0,"size":25,"total":3478,"feeStatusKnown":true}
```
`status`: `REGISTERED`, `LATE_REQUEST` (pending late request), `PAID_NOT_REGISTERED`, `FEE_CONFIRMING`, `NOT_PAID`,
`NOT_REGISTERED` (when fee statuses are not available: `feeStatusKnown:false`, and `paid` / `paidNotRegistered` are null).
`q` matches name or matric number.
### `GET /registration/report.csv?…same filters…` → "Export": `matric_number,full_name,programme,level,centre,status,courses,units,submitted_at,course_codes`.
### `GET /registration/students/{studentId}?semesterId=` (A-07b) → `{"student":{…},"registration":<registration with history>|null,"requests":[<request>],"carryOvers":[…]}`.

### `GET /registration/rules?programmeId=&semesterId=` (A-05, A-05b)
```json
{"semester":{…},"window":{"opensOn","closesOn","lateClosesOn","changesCloseOn","changesCloseOnDefault":true},
 "fee":{"blockRegistration":true,"blockExams":true,"previewsWhenUnpaid":true},
 "programme":null,"inheritedFromDefault":false,
 "levels":[{"level":100,"minUnits":15,"maxUnits":20,"maxElectives":3,"compulsoryUnits":13,"fits":true}],
 "programmesWithOwnLimits":[{"id","name"}],
 "note":"Carry-over courses always count towards the maximum."}
```
The window dates come from Academic setup (W-11b) and are shown here read-only; only `changesCloseOn` is set here. `levels` lists
every level of the programme (all levels 100–900 used by any programme for the default). `compulsoryUnits` and `fits` are worked
out per programme (for the default: the largest compulsory load of any programme at that level).
### `PUT /registration/rules` `{"semesterId":null,"changesCloseOn":"2027-02-27","fee":{…},"programmeId":null,"levels":[{"level":200,"minUnits":15,"maxUnits":20,"maxElectives":3}]}` → the rules.
Checks (`400 invalid`, A-05c, `fields` keyed `levels[0].maxUnits` …): minimum 0–60, maximum 1–60, minimum ≤ maximum ("The minimum
(22) is above the maximum (20)."), electives 0–20; compulsory units above the maximum ("200L compulsory courses already come to 23
units, over the maximum of 20."); `changesCloseOn` on or after the late close ("Course changes must stay open until registration
closes (26 Feb)."). Defaults (never saved): 15 to 20 units and up to 3 electives at every level. Audited `REGISTRATION_RULES_UPDATED`.
### `DELETE /registration/rules/programmes/{programmeId}` → `204` (the programme follows the default again).

---

## 7. Carry-overs (until B6 results produce them)

- `GET /registration/carry-overs?studentId=` → `{"items":[{"id","studentId","courseId","code","title","units","semester":1,"fromSession":"2025/2026","source":"ADMIN|IMPORT|RESULTS","status":"OPEN|CLEARED","createdAt","createdBy"}]}`.
- `POST /registration/carry-overs` `{"studentId","courseCode":"CSC 103","fromSession":"2025/2026"}` → `201`. `404` unknown student; `400 fields.courseCode` "CSC 999 is not in the catalogue."; `409 already_open`.
- `DELETE /registration/carry-overs/{id}` → `204` (not once it is on a submitted registration: `409 in_use`).
- Import: `POST /registration/uploads` `{"fileName","contentType":"text/csv","size"}` → presigned PUT (CSV, 5 MB); then
  `POST /registration/carry-overs/imports` `{"key","fileName"}` → `{"totalRows":40,"added":38,"skipped":2,"problems":[{"row":7,"message":"Matric number WU/ODL/24/9999 not found"}]}`
  (good rows saved at once; rows already open are skipped silently). Template `GET /registration/carry-overs/template.csv`: `matric_number,course_code,from_session`.
- Admin only (`ODEL_ADMIN`; director may read). Audited `CARRY_OVER_ADDED`, `CARRY_OVER_REMOVED`, `CARRY_OVERS_IMPORTED`.

---

## 8. Move a student to another centre (A-06, student-service)

### `POST /students/{id}/move-centre` `{"centreCode":"LAG","reason":"Moved to Lagos for work","effectiveOn":"2027-03-03"}` (ODEL_ADMIN)
```json
{"move":{"id","from":{"id","code","name"},"to":{"id","code","name"},"reason","effectiveOn":"2027-03-03","status":"DONE|SCHEDULED",
         "requestedBy":"Ngozi Adebayo","createdAt","appliedAt",
         "notified":[{"name":"Adeola Okafor","role":"Student","email"},{"name":"Mrs Bisi Ade","role":"Coordinator, Akure Study Centre","email"},
                     {"name":"Mr Tayo Lawal","role":"Coordinator, Lagos (Ikeja)","email"}],
         "notNotified":["Lagos (Ikeja) has no coordinator in 2026/2027: the ODeL office covers it."],
         "summary":"Exams and tutorials move to Lagos (Ikeja) from 3 Mar"},
 "record":<the student record (W-22b), now with "centreMoves">}
```
- `effectiveOn` defaults to today; it may be up to 30 days back or in the future. Today or earlier → applied at once (`DONE`);
  later → `SCHEDULED`, applied by a job early on that day (Africa/Lagos). The emails go out when the move is recorded
  (`student.centre_moved` to the student; `staff.centre_move` to the coordinator of the old centre and of the new centre).
- On applying: the record's centre changes, the history keeps the move, it is audited `STUDENT_CENTRE_CHANGED`, and the student's
  pending late and change requests (and the current registration's centre) move to the new centre, so the new coordinator decides them.
- Errors: `400 invalid` (`fields.centreCode` "Choose a centre from Centres.", `fields.reason` "Say why the student is moving (3 to 500
  characters).", `fields.effectiveOn` "Choose a date no more than 30 days back."), `409 same_centre` ("Adeola Okafor is already at
  Akure Study Centre."), `409 move_scheduled` ("A move to Lagos (Ikeja) on 3 Mar is already scheduled. Cancel it first.").
- `GET /students/{id}/centre-moves` → `{"items":[<move>]}` newest first. `POST /students/{id}/centre-moves/{moveId}/cancel` → the move (`CANCELLED`, only while `SCHEDULED`).
- `GET /students/{id}` gains `"centreMoves":[<move>]`.
- **`PATCH /students/{id}` with a different `centreCode` now goes through the same flow**: it needs `"centreChangeReason"`
  (`400 fields.centreChangeReason` "Say why the student is moving centre.") and an optional `"centreEffectiveOn"`; the same emails,
  history and request hand-over follow.

---

## Internal endpoints (`/internal/**`, `X-Internal-Key`, university in `X-Odel-Tenant-Id`)

### registration-service (:9307) provides
- `GET /internal/registration/students/{studentId}/courses?semesterId=` → `{"registered":true,"registrationId","slipNumber","courses":[{"courseId","code","title","units","kind"}],"totalUnits":18}`
  (`semesterId` default: the current registration semester; not registered → `registered:false`, empty list). For later phases (classes, exams, results).
- `GET /internal/registration/students/{studentId}/summary` → `{"semesterId","semesterLabel","state":"OPEN","registered":true,"courseCount":7,"totalUnits":18,"slipNumber","pendingRequests":0}` (Home, dashboards).
- `GET /internal/registration/summary?semesterId=` → `{"semesterId","registered":2846,"lateRequestsPending":5,"changeRequestsPending":1,"byCentre":[{"centreId","registered":34}]}` (checklists, dashboards).
- `POST /internal/registration/students/{studentId}/centre-moved` `{"centreId","centreCode","centreName"}` → `{"requestsMoved":2,"registrationsMoved":1}` (student-service, on a move).

### registration-service uses from payment-service (:9306) — **expected from the payments half**
- `GET /internal/payments/fee-status?studentId=&semesterId=` → `{"studentId","semesterId","status":"PAID|NOT_PAID|CONFIRMING","paid":true,"reference":"WU-PF-88213","paidAt":"…"}`.
  `studentId` is student-service's student id; `semesterId` academic-service's semester id. A refunded duplicate leaves the fee `PAID`;
  a reversal makes it `NOT_PAID`. No fee record → `NOT_PAID` (200, not 404). Only `status` and `paid` are required.
- Optional, for A-07's "Paid, not registered": `POST /internal/payments/fee-status/bulk` `{"semesterId","studentIds":[…]}` →
  `{"items":[{"studentId","status","paid"}]}`. Until it exists (404 / unreachable) A-07 shows `feeStatusKnown:false`.
- Switch: `ODEL_FEE_CHECK=http` (default: the call above) or `stub` (built in: every student `PAID`, or `ODEL_FEE_STUB_DEFAULT=NOT_PAID`).
  In the dev profile, `PUT /api/v1/dev/registration/fee-status` `{"studentId","semesterId","status":"PAID|NOT_PAID|CONFIRMING"}`
  (ODeL admin) overrides the answer for one student (both modes; `DELETE` same path with `{"studentId","semesterId"}` removes it). The e2e test uses it.

### registration-service uses from academic-service (:9304) — new
- `GET /internal/academic/calendar` → `{"currentSession":{"id","name","status","semesters":[{"id","number","name","startsOn","endsOn","regOpensOn","regClosesOn","lateRegClosesOn"}]}|null}` (the ACTIVE session only).
- `GET /internal/academic/semesters/{id}` → `{"id","sessionId","sessionName","sessionStatus","number","name","startsOn","endsOn","regOpensOn","regClosesOn","lateRegClosesOn"}`.
- `GET /internal/academic/offerings?programmeId=&level=&semester=1` → `{"items":[{"id","code","title","units","level","semester","kind":"COMPULSORY|ELECTIVE"}]}`.
- `GET /internal/academic/courses?ids=a,b` · `GET /internal/academic/courses?code=CSC103` → `{"items":[{"id","code","title","units","level","semester"}]}`.
- `GET /internal/academic/programmes/{id}/levels` is not needed: `GET /internal/academic/reference` already lists levels.
- `GET /internal/academic/coordinators?sessionId=` → `{"session":{"id","name"},"items":[{"centreId","centreCode","centreName","userId"|null}]}` (every centre, current session by default).

### registration-service uses from student-service (:9305) — new
- `GET /internal/students/{id}` and `GET /internal/students/by-user/{userId}` → `{"id","userId","matricNumber","fullName","firstName","email","programmeId","programmeCode","programmeName","level","centreId","centreCode","centreName","status"}`.
- `GET /internal/students/roster` → `{"items":[<same>]}` (every student of the university, for A-07 and the carry-over import).
- `GET /internal/students/by-matric?matric=` → the same (404 if none).

### identity-service, tenant-service, notification-service (unchanged)
`GET /internal/users/{id}` (staff name and email), `GET /internal/tenants/{id}` (name, colours, `portalUrl`, `logoUrl`),
`POST /internal/messages`.

## Notification templates (notification-service `005-registration-templates.sql`)
Email and bell (`IN_APP`) for each; variables in `{{…}}`:
| Key | To | Variables |
|---|---|---|
| `registration.confirmed` | student | `firstName, semesterLabel, courseCount, totalUnits, slipNumber, slipUrl, courseList, changesCloseOn` |
| `registration.late_approved` | student | `firstName, semesterLabel, decidedBy, decidedOn, registerBy, registerUrl` |
| `registration.late_declined` | student | `firstName, semesterLabel, decidedBy, note, requestUrl` |
| `registration.change_approved` | student | `firstName, semesterLabel, changeSummary, decidedBy, totalUnits, slipUrl` |
| `registration.change_declined` | student | `firstName, semesterLabel, changeSummary, decidedBy, note, requestUrl` |
| `registration.request_new` | coordinator | `fullName, studentName, matricNumber, kindLabel, centreName, summary, queueUrl` |
| `student.centre_moved` | student | `firstName, fromCentre, toCentre, effectiveOn, reason, centrePhone` |
| `staff.centre_move` | coordinators | `fullName, studentName, matricNumber, fromCentre, toCentre, effectiveOn, direction` ("joins your centre" / "leaves your centre") |

## Audit actions added
`REGISTRATION_SUBMITTED`, `REGISTRATION_LATE_REQUESTED`, `REGISTRATION_CHANGE_REQUESTED`, `REGISTRATION_REQUEST_CANCELLED`,
`REGISTRATION_LATE_APPROVED`, `REGISTRATION_LATE_DECLINED`, `REGISTRATION_CHANGE_APPROVED`, `REGISTRATION_CHANGE_DECLINED`,
`REGISTRATION_RULES_UPDATED`, `CARRY_OVER_ADDED`, `CARRY_OVER_REMOVED`, `CARRY_OVERS_IMPORTED`; student-service `STUDENT_CENTRE_CHANGED`
(now with reason and effective date), `STUDENT_CENTRE_MOVE_SCHEDULED`, `STUDENT_CENTRE_MOVE_CANCELLED`.
