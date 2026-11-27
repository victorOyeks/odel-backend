# API contract · B1 Academic setup (staff side)

B1 is split in two halves. This file is the **academic setup** half: sessions, structure, catalogue, centres, staffing,
grading rules, the setup checklist (W-10 to W-17e), and the staff side of identity (People & roles W-15/W-21, two-step reset,
the university's audit log). The students half (import, welcome emails, student accounts) is `docs/api-b1-students.md`.

Same conventions as `docs/api-b0.md`: paths start with `/api/v1`, `X-Odel-Host` on every call, CSRF header on every non-GET,
errors `{"error","message","fields"}`, lists `{"items","page","size","total"}`, dates `YYYY-MM-DD` (calendar days in the
university's zone, Africa/Lagos), times ISO-8601 with offset.

**Who may call:** everything here needs a signed-in staff session. Writes need the active role `ODEL_ADMIN`; reads also allow
`ODEL_DIRECTOR` (and `GET /academic/sessions`, `GET /academic/structure`, `GET /academic/courses`, `GET /academic/centres`
allow every staff role, because other screens need them). Anything else → `403 forbidden`. Ids from another university → `404`.

## Services and routes

| Service | Port | Schema | Gateway prefixes |
|---|---|---|---|
| `academic-service` (new) | 9304 | `academic` | `/api/v1/academic/**`, `/api/v1/setup/**`, `/api-docs/academic` |
| `identity-service` | 9301 | `identity` | adds `/api/v1/people/**`, `/api/v1/audit` |

`/api/v1/setup/**` moved from tenant-service to academic-service (same response shape as B0, now filled from real data).

---

## 1. Sessions and semesters (W-11, W-11b, W-11c)

A session has exactly two semesters (first, second). Each semester has start and end dates and a registration window
(opens, closes, and an optional late close: after "closes", late registration needs coordinator approval until "late closes").
Statuses: `DRAFT` (being set up), `ACTIVE` (the one students see; only one at a time), `ARCHIVED` (an older active one, set
automatically when another is made active).

### `GET /academic/sessions`
```json
{"items":[{
  "id":"uuid","name":"2026/2027","status":"DRAFT","startsOn":"2026-10-05","endsOn":"2027-07-30","current":true,
  "semesters":[
    {"id":"uuid","number":1,"name":"First semester","startsOn":"2026-10-05","endsOn":"2027-02-26",
     "registration":{"opensOn":"2027-02-02","closesOn":"2027-02-16","lateClosesOn":null}},
    {"id":"uuid","number":2,"name":"Second semester","startsOn":"2027-03-15","endsOn":"2027-07-30","registration":null}],
  "semestersLabel":"1st: Oct–Feb · 2nd: Mar–Jul","registrationLabel":"2 Feb – 16 Feb 2027","complete":true}]}
```
Newest first. `current` marks the session being set up / run: the ACTIVE one if any, otherwise the newest DRAFT.
`registrationLabel` is `"Closed"` when the window is in the past, `"Not set"` when there is none. `complete` = both semesters have
dates and at least one semester has a registration window.

### `POST /academic/sessions` (W-11c) · `PUT /academic/sessions/{id}` (W-11b)
```json
{"name":"2026/2027",
 "semesters":[
   {"number":1,"startsOn":"2026-10-05","endsOn":"2027-02-26","registration":{"opensOn":"2027-02-02","closesOn":"2027-02-16","lateClosesOn":"2027-02-23"}},
   {"number":2,"startsOn":"2027-03-15","endsOn":"2027-07-30"}],
 "makeActive":false}
```
"Save draft" sends `makeActive:false`; "Save and make active" sends `true` (needs both semesters complete). The second semester
(or its dates) may be left out while a draft. `201`/`200` → the session as in the list.

Checks (`400 invalid` with `fields`, keys like `name`, `semesters[0].endsOn`, `semesters[0].registration.closesOn`):
- name like `2026/2027` (two years, the second one more): "Write the session as 2026/2027."; unique: `409 name_taken` "There is already a 2026/2027 session."
- each semester ends after it starts; the second starts after the first ends ("The second semester must start after the first one ends.");
- registration opens on or before it closes, the late close is on or after the close, and the window closes on or before the semester ends;
- no overlap with another session's dates: `409 overlap` "These dates overlap 2025/2026 (5 Oct 2025 – 30 Jul 2026). Change the dates so sessions follow one another.";
- `makeActive` with a missing semester: `400` "Add both semesters' dates before making the session active."

### `POST /academic/sessions/{id}/activate`
Makes it the active session; the previously active one becomes `ARCHIVED`. Same completeness check. Returns the session.

### `DELETE /academic/sessions/{id}`
Only a `DRAFT` with no staffing → `204`; else `409 in_use` "This session already has staff assigned; it cannot be deleted."

---

## 2. Academic structure (W-12 … W-12j)

### `GET /academic/structure`
The tree for W-12:
```json
{"faculties":[{"id","name":"Faculty of Science","code":"SCI","departmentCount":1,
  "departments":[{"id","name":"Department of Computer Science","code":"CSC","programmeCount":1,
    "programmes":[{"id","name":"B.Sc. Computer Science","code":"BSC-CSC","degree":"B.Sc.","durationYears":4,
                   "levels":[100,200,300,400],"entryLevels":[100],"minUnitsToGraduate":null,"status":"ACTIVE","courseCount":1}]}]}],
 "totals":{"faculties":1,"departments":1,"programmes":1}}
```

### Faculties · departments · programmes
- `POST /academic/faculties` `{"name":"Faculty of Science","code":"SCI"}` → `201` faculty. `PUT /academic/faculties/{id}` same body (rename).
- `POST /academic/departments` `{"facultyId","name","code"}` → `201`. `PUT /academic/departments/{id}` (may move to another faculty).
- `POST /academic/programmes` `{"departmentId","name":"B.Sc. Computer Science","code":"BSC-CSC","degree":"B.Sc.","durationYears":4,"entryLevels":[100,200],"minUnitsToGraduate":120}` → `201`.
  `levels` are worked out from the duration (4 years → 100L–400L). `entryLevels` default `[100]` and must be within the levels.
- `PUT /academic/programmes/{id}` same body.
- `GET /academic/programmes/{id}` (W-12 right panel):
```json
{"id","name","code","degree","durationYears":4,"durationLabel":"4 years · 8 semesters","levels":[100,200,300,400],"entryLevels":[100],
 "entryLevelsLabel":"100L","minUnitsToGraduate":null,"status":"ACTIVE","gradingRules":"Default 5-point scale",
 "department":{"id","name"},"faculty":{"id","name"},
 "byLevel":[{"level":200,"semester":1,"courses":1,"units":3,"lecturersAssigned":1}]}
```
Codes: letters, digits and hyphens, unique per kind in the university (`409 code_taken` "SCI is already used by Faculty of Science.").
Names unique within their parent (`409 name_taken`). Duration 1–7 years.

### CSV import of the structure (W-12c)
Uploads go browser → storage. Then the file is checked; nothing is saved until confirmed.

1. `POST /academic/uploads` `{"fileName":"structure_2026.csv","contentType":"text/csv","size":20480}` →
   `{"key":"imports/…/uuid.csv","uploadUrl","method":"PUT","headers":{"Content-Type":"text/csv"},"expiresAt"}` (CSV, at most 5 MB). PUT the file there.
2. `POST /academic/structure/imports` `{"key","fileName"}` → the check (W-12c):
```json
{"id":"uuid","kind":"STRUCTURE","fileName":"structure_2026.csv","status":"CHECKED","totalRows":48,"readyRows":45,"problemRows":3,
 "problems":[{"row":12,"message":"Department \"Comp. Science\" not found: add it in the file or on the Structure screen"},
             {"row":30,"message":"Programme code BSC-CSC appears twice in the file (rows 30 and 31)"}],
 "summary":"3 rows need fixing","createdAt","confirmedAt":null}
```
3. `POST /academic/imports/{id}/confirm` ("Import the rest") → the import with `status:"IMPORTED"` and `imported:{"faculties":1,"departments":1,"programmes":1}`. Only the ready rows are saved. A second confirm → `409 already_done`.
4. `POST /academic/imports/{id}/cancel` → `204`. `GET /academic/imports/{id}` → the same body.
5. `GET /academic/imports/{id}/problems.csv` → the problem rows as CSV with an extra `problem` column ("Download problem rows").
6. `GET /academic/imports/templates/structure.csv` → the template ("Download template").

Structure template columns: `faculty_name,faculty_code,department_name,department_code,programme_name,programme_code,degree,duration_years,entry_levels`
(one row per programme; `entry_levels` like `100;200`). A row may refer to a faculty or department that already exists (by code) or
that an earlier row of the file creates. Row checks: required cells, codes, duration 1–7, entry levels, faculty code reused with a
different name, department under two faculties, programme code already in the university or twice in the file.
A file with no data rows → `400 empty_file` "The file has no rows under the header."; wrong header → `400 bad_header` "The columns do not match the template: download the template and copy your rows into it."

---

## 3. Course catalogue (W-13 … W-13e)

### `GET /academic/courses?search=&programmeId=&level=&semester=&lecturer=any|none|<userId>&page=0&size=25`
```json
{"items":[{"id","code":"CSC 201","title":"Data Structures","units":3,"level":200,"semester":1,"semesterLabel":"First",
  "offeredTo":[{"programmeId","programmeName":"B.Sc. Computer Science","kind":"COMPULSORY"}],
  "lecturer":{"id","fullName":"Dr Funmi Bello","status":"ACTIVE"},"needsLecturer":false}],
 "page":0,"size":25,"total":1,"catalogueTotal":212,"programmeTotal":18}
```
`lecturer` is for the current session (null → "No lecturer"). Content status (W-13's last column) arrives with B3.

### `POST /academic/courses` (W-13b, W-13d) · `PUT /academic/courses/{id}` (W-13e) · `GET /academic/courses/{id}`
```json
{"code":"CSC 201","title":"Data Structures","units":3,"level":200,"semester":1,
 "offeredTo":[{"programmeId":"uuid","kind":"COMPULSORY"}],"lecturerId":null}
```
`kind`: `COMPULSORY` (Core) or `ELECTIVE`. `lecturerId` (optional) assigns the lecturer for the current session (same rules as Staffing).
Codes are stored as `ABC 123` (letters, a space, digits; "csc201" becomes "CSC 201"); duplicate → `409 code_taken`
"CSC 201 is already in the catalogue (Data Structures)." Units 1–12; level one of the levels of the programmes it is offered to
(100–900); semester 1 or 2.

### CSV import of the catalogue (W-13c)
Same flow as the structure: upload, `POST /academic/courses/imports {"key","fileName"}`, then `/academic/imports/{id}/confirm`.
Template `GET /academic/imports/templates/catalogue.csv`, columns `code,title,units,level,semester,programme_code,kind`
(one row per course and programme; a course offered to two programmes has two rows with the same code, title, units, level and
semester). Row checks: code shape, units, level, semester (`1`/`2`/`First`/`Second`), programme code not found, kind
(`compulsory`/`core`/`elective`), the same code with different details in two rows, a code already in the catalogue.

---

## 4. Study centres and rooms (W-14 … W-14d)

- `GET /academic/centres` → `{"items":[{"id","name":"Akure Study Centre","code":"AKR","address","state":"Ondo","phone",
  "coordinator":{"id","fullName":"Mrs Bisi Ade"}|null,"rooms":1,"seats":30,"computers":30,"students":0}]}`
  (`coordinator` from Staffing for the current session; `students` from the students service, 0 until it answers).
- `GET /academic/centres/{id}` → the centre with `"roomsList":[{"id","name":"Room 1","seats":30,"computers":30}]`.
- `POST /academic/centres` `{"name","code","address","state","phone"}` → `201`. `PUT /academic/centres/{id}` same body.
  Code: 2–6 letters/digits, unique (`409 code_taken`). Phone optional, Nigerian format (`0803 123 4567` or `+234…`). State one of the 36 states or FCT.
- `POST /academic/centres/{id}/rooms` `{"name":"Room 1","seats":30,"computers":30}` → `201` room. `PUT /academic/rooms/{id}`; `DELETE /academic/rooms/{id}` → `204`.
  Seats 1–2000; computers 0–seats ("A room cannot have more computers than seats."). Room names unique within the centre.

---

## 5. Staffing (W-16 … W-16e)

Staffing is per session (default: the current session; pass `?sessionId=` for another). Staff are picked from People & roles:
the lecturer must hold the `LECTURER` role, facilitators `FACILITATOR`, the exam officer `EXAM_OFFICER`, a coordinator
`CENTRE_COORDINATOR`; suspended staff cannot be picked (`400` with `fields`: "Dr X does not have the Lecturer role. Give it on People & roles first." /
"Mr Segun Afolabi is suspended.").

### `GET /academic/staffing?sessionId=`
```json
{"session":{"id","name":"2026/2027"},
 "courses":[{"course":{"id","code":"CSC 201","title":"Data Structures","level":200,"semester":1},
   "lecturer":{"id","fullName":"Dr Funmi Bello","status":"ACTIVE"},
   "facilitators":[{"centreId","centreName":"Akure Study Centre","user":{"id","fullName","status"}}],
   "examOfficer":null,"needsLecturer":false}],
 "centres":[{"centre":{"id","name","code"},"coordinator":{"id","fullName","status"},"students":0,"needsCoordinator":false}],
 "needLecturer":0,"needCoordinator":0,"studentsImported":false,
 "summary":"All 1 courses have a lecturer"}
```
`studentsImported:false` is W-16e: facilitators per centre are set once students are imported ("No students imported yet for 2026/2027 · 0 centres"); the
web app still lets the admin pick them. A user shown as `"status":"SUSPENDED"` is drawn in red (W-16).

### `PUT /academic/staffing/courses/{courseId}` (W-16b)
`{"sessionId":null,"lecturerId":"uuid|null","facilitators":[{"centreId","userId"}],"examOfficerId":"uuid|null","notify":true}`
Replaces that course's staffing for the session. One facilitator per centre. `notify` emails each newly assigned person
(template `staff.assigned`). Returns the course row.

### `PUT /academic/staffing/centres/{centreId}` (W-16c)
`{"sessionId":null,"coordinatorId":"uuid|null","notify":true}` → the centre row.

### `POST /academic/staffing/copy` (W-16d)
`{"toSessionId":null,"fromSessionId":null}` (defaults: to the current session, from the session before it) →
`{"copied":14,"skipped":[{"what":"CSC 203 · facilitator for Lagos","reason":"Mr Segun Afolabi is suspended"}],"from":"2025/2026","to":"2026/2027"}`.
Only fills empty places; never overwrites what is already set. No earlier session → `409 no_previous_session` "There is no earlier session to copy from."

### `GET /academic/staff-options?role=LECTURER&departmentId=`
People who may be picked for a role (from identity-service), active and invited (not suspended), those scoped to `departmentId` first:
`{"items":[{"id","fullName","email","status":"ACTIVE","scopeLabel":"Computer Science"}]}`.

---

## 6. Grading rules (W-17 … W-17e)

Rules are versioned: every save adds a new version that applies from an effective session (default: the current session).
Results computed later (B6) use the version in force for their session. `GET` returns the newest version.

### `GET /academic/grading?programmeId=`
```json
{"scope":"DEFAULT","programme":null,"version":1,"saved":false,"effectiveSession":{"id","name":"2026/2027"},
 "caWeight":30,"examWeight":70,"passMark":40,"scale":5,"roundTotals":true,
 "bands":[{"grade":"A","from":70,"to":100,"point":5,"remark":"Excellent"},{"grade":"B","from":60,"to":69,"point":4,"remark":"Very good"},
          {"grade":"C","from":50,"to":59,"point":3,"remark":"Good"},{"grade":"D","from":45,"to":49,"point":2,"remark":"Fair"},
          {"grade":"E","from":40,"to":44,"point":1,"remark":"Pass"},{"grade":"F","from":0,"to":39,"point":0,"remark":"Fail (carry-over)"}],
 "standing":{"probationBelow":1.00,"withdrawAfterProbationSemesters":2,"carryOverBeforeGraduating":true,
   "classes":[{"name":"First class","from":4.50},{"name":"Second class upper","from":3.50},{"name":"Second class lower","from":2.40},{"name":"Third class","from":1.50},{"name":"Pass","from":1.00}]},
 "summary":"Default 5-point scale (30% CA / 70% exam)","programmesWithOwnRules":0,"inheritedFromDefault":false,
 "example":"CA 24 + exam 51 = 75 → A (5 points)"}
```
`saved:false` = the built-in default that nobody has reviewed yet. With `programmeId` and no override: the default's values with
`"scope":"PROGRAMME","inheritedFromDefault":true`.

### `PUT /academic/grading` (default) · `PUT /academic/grading/programmes/{programmeId}` (W-17d override)
Body: everything above from `caWeight` to `standing`, plus optional `effectiveSessionId`. Returns the new version.
Checks (`400 invalid`, W-17c):
- `caWeight + examWeight` must be 100: `fields.caWeight` "Coursework and exam add up to 90%. They must add up to 100%." (the message carries the real sum);
- bands must cover 0 to 100 with no gaps or overlaps, each `from ≤ to`, whole numbers: "Scores 40 to 44 are in no band." / "Bands B (60–69) and C (55–64) overlap at 60 to 64." / "The top band must end at 100." / "The bottom band must start at 0.";
- grades unique; points 0 to `scale`, and a higher band never has fewer points;
- pass mark 0–100 and the band that contains the pass mark has points above 0 ("The pass mark is 40 but scores of 40 get 0 points.");
- standing: probation 0–scale; classes strictly descending; withdraw-after 1–6 semesters.

### `DELETE /academic/grading/programmes/{programmeId}` → `204`: the programme follows the default again (recorded as a new version).
### `GET /academic/grading/history?programmeId=` → `{"items":[{"version","effectiveSession","savedAt","savedBy":{"fullName"},"caWeight","examWeight","passMark","summary"}]}`.

---

## 7. Setup checklist (W-10)

### `GET /setup/checklist`
Same shape as B0, now from real data, plus `sessionName` (for "Set up 2026/2027") and `percent`:
```json
{"sessionName":"2026/2027","steps":[
 {"number":1,"key":"SESSION","title":"Session and semesters","summary":"2026/2027 · 2 semesters · registration 2–16 Feb","status":"DONE","actionLabel":"Edit","href":"/staff/academic-setup/sessions"},
 {"number":2,"key":"STRUCTURE","title":"Academic structure","summary":"4 faculties · 11 departments · 18 programmes","status":"DONE","actionLabel":"Edit",…},
 {"number":3,"key":"CATALOGUE","summary":"212 courses",…},
 {"number":4,"key":"CENTRES","summary":"28 centres · 3 without a coordinator",…},
 {"number":5,"key":"PEOPLE","summary":"64 staff invited · 51 active",…},
 {"number":6,"key":"STAFFING","summary":"19 courses still need a lecturer","status":"IN_PROGRESS","actionLabel":"Continue",…},
 {"number":7,"key":"GRADING","summary":"Default 5-point scale (30% CA / 70% exam)","status":"NOT_STARTED","actionLabel":"Review",…},
 {"number":8,"key":"IMPORT","summary":"From the university portal CSV","status":"LOCKED","lockedReason":"Finish steps 1, 2 and 4 first",…},
 {"number":9,"key":"WELCOME","status":"LOCKED","lockedReason":"Import students first",…}],
 "done":5,"total":9,"percent":56}
```
`actionLabel`: `Start` (not started), `Continue` (in progress), `Edit` (done), `Review` (grading not yet saved), null when locked.

| Step | NOT_STARTED | IN_PROGRESS | DONE | Summary when started |
|---|---|---|---|---|
| 1 Session | no session | current session incomplete | both semesters dated and a registration window | `2026/2027 · 2 semesters · registration 2–16 Feb` |
| 2 Structure | no faculty | a faculty without departments or a department without programmes | ≥1 programme, none empty | `1 faculty · 1 department · 1 programme` |
| 3 Catalogue | no course | a course offered to no programme, or a programme with no course | otherwise | `212 courses` (`· 3 not offered to any programme`) |
| 4 Centres | no centre | a centre without rooms | every centre has a room | `28 centres · 3 without a coordinator` |
| 5 People | nobody but ODeL admins/directors | staff invited, no lecturer yet | ≥1 lecturer | `64 staff invited · 51 active` |
| 6 Staffing | nothing assigned in the current session | courses without a lecturer or centres without a coordinator | none missing | `19 courses still need a lecturer · 3 centres need a coordinator` |
| 7 Grading | default never saved ("Review") | — | default saved | `Default 5-point scale (30% CA / 70% exam)` |
| 8 Import | — | — | students imported | `3,478 students imported` |
| 9 Welcome | — | some sent | none left unsent | `3,402 sent · 12 failed · 64 not sent` |

Step 8 is `LOCKED` until steps 1, 2 and 4 are done ("Finish steps 1, 2 and 4 first": students need a session, a programme and a
centre); step 9 until students exist ("Import students first"). Steps 5, 8 and 9 read their numbers from identity-service and the
students service; if one is down the step shows its default summary and is not counted done.

---

## 8. People & roles (identity-service; W-15, W-15b, W-15c, W-21)

Writes need `ODEL_ADMIN`; reads also `ODEL_DIRECTOR`.

### `GET /people?search=&role=&status=&page=0&size=25`
```json
{"items":[{"id","fullName":"Dr Funmi Bello","email":"f.bello@wesleyuni.edu.ng","initials":"FB","status":"ACTIVE",
  "roles":[{"id","role":"LECTURER","roleLabel":"Lecturer","scopeType":"COURSE","scopeId","scopeLabel":"CSC 201 · Computer Science"}],
  "scopeLabel":"CSC 201 · Computer Science","twoStepSetUp":true,"invitedAt","acceptedAt","lastSignInAt","suspendedAt":null,"suspendedReason":null}],
 "page":0,"size":25,"total":64,"counts":{"total":64,"active":51,"invited":12,"suspended":1}}
```
`status`: `INVITED` (has not signed in yet), `ACTIVE`, `SUSPENDED`. `search` matches name or email.
`GET /people/{id}` → `{"person":<the row above>,"staffing":[{"kind":"LECTURER","what":"Lecturer · CSC 201 · Data Structures","session":"2026/2027","current":true}]}` (staffing from academic-service).

### `POST /people` (W-15b Invite)
```json
{"fullName":"Dr Funmi Bello","email":"f.bello@wesleyuni.edu.ng",
 "roles":[{"role":"LECTURER","scopeType":"COURSE","scopeId":"<course id>"}]}
```
→ `201` the person; the invitation email (template `staff.invitation`, role label like "Lecturer for CSC 201 · Computer Science") and
a bell item go out. Roles that can be given here: `LECTURER, FACILITATOR, EXAM_OFFICER, CENTRE_COORDINATOR, HOD, DEAN, ODEL_DIRECTOR,
ODEL_ADMIN, MANAGEMENT, REGISTRY, COUNSELLOR` (never platform roles or `STUDENT`). Scope types per role:

| Role | Scope |
|---|---|
| `LECTURER`, `FACILITATOR`, `EXAM_OFFICER` | `UNIVERSITY`, `FACULTY`, `DEPARTMENT` or `COURSE` |
| `CENTRE_COORDINATOR` | `CENTRE` |
| `HOD` | `DEPARTMENT` |
| `DEAN` | `FACULTY` |
| others | `UNIVERSITY` |

`scopeId` must be a faculty/department/course/centre of this university (checked with academic-service; `400` "Choose a course from
the catalogue."). The scope label is worked out by the server: `CSC 201 · Computer Science`, `Akure Study Centre`,
`Department of Computer Science`, `Faculty of Science`, or the university's name.
Errors: email outside the university's Google domain → `400` `fields.email` "Use an @wesleyuni.edu.ng address: staff sign in with the university's Google accounts.";
already has an account → `409 email_taken` "f.bello@wesleyuni.edu.ng already has an account here."

### Bulk invite by CSV (W-15c)
`POST /people/uploads` `{"fileName","contentType":"text/csv","size"}` → presigned PUT (as `/academic/uploads`).
`POST /people/imports` `{"key","fileName"}` → `{"id","totalRows","readyRows","problemRows","problems":[{"row","message"}],"status":"CHECKED"}` (nothing saved, kept for one hour);
`POST /people/imports/{id}/confirm` → the same body with `"status":"IMPORTED","invited":12` (rows that became invalid meanwhile are left out); `GET /people/imports/template.csv`.
Columns `full_name,email,role,scope_type,scope_code` (`scope_code`: a course code, centre code, department or faculty code; empty for `UNIVERSITY`).
Row checks: name, email domain, email already here or twice in the file, unknown role, wrong scope for the role, scope code not found.

### `PUT /people/{id}/roles` (W-15 Edit roles)
`{"roles":[{"role","scopeType","scopeId"}]}` replaces the person's roles (at least one). Each added or removed role is audited
(`ROLE_GRANTED`, `ROLE_REMOVED`). If a role is removed the person is signed out everywhere (their next sign-in shows their new roles).
You cannot remove your own `ODEL_ADMIN` role (`409 own_admin_role` "You cannot remove your own ODeL admin role. Ask another ODeL admin.").

### `GET /people/{id}/suspend-check` (W-21)
`{"warnings":["They facilitate 2 course groups (CSC 201 · Data Structures · Akure Study Centre, …). Reassign them so students are not left without support."],"staffing":[…current session places…]}` (also warns about courses they lecture and centres they coordinate).

### `POST /people/{id}/suspend` `{"reason":"Left the university on 30 Nov 2026"}` → the person (`SUSPENDED`).
Signed out at once everywhere; sign-in then ends with `?error=suspended`. Reason required (1–500 characters), kept in the audit log.
You cannot suspend yourself (`409 self`). Already suspended → `409 already_suspended`.

### `POST /people/{id}/reinstate` → the person (`ACTIVE`, or `INVITED` if they never signed in).

### `POST /people/{id}/resend-invitation` → `204`. Only while `INVITED` (`409 already_active` "Funmi Bello has already signed in.").

### `POST /people/{id}/reset-two-step` `{"reason":"Lost phone"}` (gap: admin resets a staff member's two-step device)
Clears their authenticator, backup codes and trusted browsers and signs them out everywhere. Their next sign-in goes to two-step set-up
(`MFA_SETUP`, W-04). They get an email (template `staff.two_step_reset`). Audited as `MFA_RESET`. Not for yourself (`409 self`): sign out and use a backup code instead.
`204`.

## 9. The university's audit log (identity-service)

### `GET /audit?type=&action=&actorId=&from=2026-11-01&to=2026-11-30&page=0&size=50` (role `ODEL_ADMIN`, `ODEL_DIRECTOR`)
```json
{"items":[{"id","at","type":"SIGN_IN","action":"SIGN_IN","summary":"Funmi Bello signed in (Google and two-step code)",
  "actor":{"id","fullName":"Funmi Bello","role":"LECTURER","roleLabel":"Lecturer"},"ip":"102.89.1.4"}],
 "page":0,"size":50,"total":120}
```
Only this university's entries; newest first; `from`/`to` are calendar days in Africa/Lagos (inclusive). `type`:
- `SIGN_IN`: `SIGN_IN, SIGN_IN_FAILED, SIGN_OUT, MFA_SET_UP, MFA_FAILED, MFA_RESET, ROLE_SWITCHED` and any action starting `STUDENT_SIGN_IN`/`STUDENT_ACTIVATED`/`PASSWORD_`;
- `ROLES`: `STAFF_INVITED, STAFF_SUSPENDED, STAFF_REINSTATED, ROLE_GRANTED, ROLE_REMOVED, INVITATION_RESENT`;
- `SETUP`: actions academic-service writes (`SESSION_*, FACULTY_*, DEPARTMENT_*, PROGRAMME_*, COURSE_*, CENTRE_*, ROOM_*, STAFFING_*, GRADING_*, IMPORT_*` for structure and catalogue);
- `STUDENTS`: actions starting `STUDENT` or `STUDENTS_` or `WELCOME_` (the students half);
- anything else is `OTHER`.

---

## Internal endpoints (`/internal/**`, `X-Internal-Key`, university in `X-Odel-Tenant-Id`)

### academic-service (:9304) provides
- `GET /internal/academic/reference` → `{"currentSession":{"id","name","status"}|null,"sessions":[{"id","name","status"}],
  "programmes":[{"id","code","name","levels":[100,200,300,400],"departmentId","departmentName","facultyId"}],
  "centres":[{"id","code","name"}]}`: for the students import (programme and centre codes) and the student record.
- `GET /internal/academic/scopes/{scopeType}/{id}` → `{"scopeType","id","label"}` (404 if not in this university).
- `GET /internal/academic/scopes/resolve?scopeType=COURSE&code=CSC201` → same (404 if not found).
- `GET /internal/academic/staffing/users/{userId}` → `{"items":[{"kind":"FACILITATOR","what":"Facilitator · CSC 201 · Akure Study Centre","session":"2026/2027","current":true}]}`.

### identity-service (:9301) provides (new)
- `GET /internal/staff?role=LECTURER` → `[{"id","fullName","email","status","roles":[{"role","scopeType","scopeId","scopeLabel"}]}]` (no role → everyone).
- `GET /internal/staff/summary` → `{"total":64,"invited":12,"active":51,"suspended":1,"byRole":{"LECTURER":30,…}}`.

### expected from the students service (:9305)
- `GET /internal/students/summary` →
  `{"total":3478,"active":120,"lastImportAt":"…","welcome":{"sent":3402,"failed":12,"notSent":64},"byCentre":[{"centreId":"uuid","students":34}]}`.
  Until it exists (connection refused or 404) academic-service treats every number as zero.

## Testing note
`AcademicSetupJourneyTest` (e2e-tests) needs the provider admin to sign in. Like B0's test it sets up the provider's two-step itself
when it is not set up yet; otherwise set `ODEL_E2E_PROVIDER_TOTP_SECRET`. Running B0's and this test in one Maven run therefore needs
that variable (the first test sets two-step up; locally, run them one at a time after clearing the provider's two-step).

## Changes to B0
- `GET /setup/checklist` moved to academic-service (same shape; adds `sessionName`, `percent`).
- tenant-service `GET /internal/tenants/{id}` also returns `portalUrl` (for invitation links).
- notification-service `004-staff-templates.sql`: templates `staff.assigned` (email + bell) and `staff.two_step_reset` (email).
- identity `002-staff-admin.sql`: `users.suspended_at`, `suspended_reason`, `invited_by`.
