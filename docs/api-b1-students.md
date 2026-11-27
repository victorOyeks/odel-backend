# API contract · B1 Students (import, welcome emails, student accounts)

Same conventions as `docs/api-b0.md`: all paths start with `/api/v1`, JSON in and out, CSRF header on every non-GET,
errors `{"error","message","fields"}`, lists `{"items","page","size","total"}`, `404` also for "another university's".
Times are ISO-8601 (UTC in the API; the web shows Africa/Lagos).

Who owns what:

| Service | Port | Owns |
|---|---|---|
| `student-service` (new) | 9305 | Student records and their status history, CSV import (template, validate, confirm, history), add / edit / suspend, the welcome email (template, test, send to all, delivery report), the student's own record |
| `identity-service` | 9301 | Student accounts (role `STUDENT`, auth `PASSWORD`): activation, sign-in, lockout, password reset, consent, sessions |
| `notification-service` | 9303 | Sends every email (codes, welcome); default templates `student.*` (changelog 003) |

Gateway routes added: `/api/v1/students`, `/api/v1/students/**`, `/api/v1/imports/**`, `/api/v1/student-profile` → student-service.
Student auth is under `/api/v1/auth/student/**` (identity-service, already routed by `/api/v1/auth/**`).
Swagger: `/api-docs/student` on the gateway's Swagger page.

Roles: everything under `/students` and `/imports` needs the active role `ODEL_ADMIN` (changes) or `ODEL_ADMIN` / `ODEL_DIRECTOR`
(reading). Anyone else gets `403 forbidden`. Students use only `/auth/student/**`, `/session`, `/auth/sign-out` and `/student-profile`.

---

## Part 1 · Student accounts (student web, D1 flows A–D)

Students sign in with matric number (or email) and password. They never use two-step codes or Google; staff never use these endpoints
(a staff account is "not found" here, and a student account is `not_linked` on Google sign-in).
The session is the same Redis session as staff (cookie `ODEL_SESSION`, `GET /session` as in B0, `stage` is always `SIGNED_IN`,
`roles` = one `STUDENT` role). Idle timeout is the same 30 minutes (design C: warning at 28, signed out at 30).
After a student signs in the web goes to Home (`/`).

Every code is 6 digits, emailed, valid **15 minutes**, can be re-sent after **60 seconds**, at most **5 codes an hour** per student
(activation and reset codes count together). A code allows **3 wrong tries**; the third wrong try sends a fresh code automatically
(when the hourly limit allows), as M-03b says ("2 tries left before we send a new one").

### `GET /auth/student/password-rules`
```json
{"minLength":8,"hint":"At least 8 characters, with a letter and a number.",
 "rules":[{"key":"length","label":"At least 8 characters"},{"key":"letter","label":"At least one letter"},{"key":"number","label":"At least one number"}]}
```
The web checks live with these rules; the API checks again (max 128 characters).

### `GET /auth/student/privacy-notice` (M-05)
```json
{"version":"2026-11","title":"How we use your information",
 "intro":"Wesley University protects your data under the Nigeria Data Protection Act 2023.",
 "points":["Your records are used to run your programme.","Reminders and codes come by email and in the app.",
           "Staff see only what they need to support you.","We never sell your data. Ask for a copy any time."]}
```

### Activation (M-01 … M-05, E-01 … E-03)

#### `POST /auth/student/activation/start` `{"login":"WU/ODL/24/0153"}`
`login` is the matric number (any case, spaces ignored) or, for "Use my email address instead", the email the university has.
- `200` (M-03) — a code is on its way:
  ```json
  {"activationId":"opaque","maskedEmail":"a•••••@gmail.com","codeSent":true,"expiresInSeconds":900,
   "resendInSeconds":60,"sendsLeftThisHour":4,"student":{"firstName":"Adeola"},
   "centre":{"name":"Akure Study Centre","phone":"0803 555 0117"}}
  ```
  Asking again within 60 seconds returns the same `activationId` with `codeSent:false` (no second email) and the seconds left.
  `centre` (may be null) is for "I can't access this email" (E-03 dialog: "The ODeL office at {centre.name} can check your admission letter and update your email").
- `404 matric_not_found` (E-01): "We could not find this number. Check it on your admission letter, or contact your study centre."
- `409 already_active` (E-02): "This account is already active. Sign in with your password, or reset it if you have forgotten it."
- `422 no_email` (E-03): "We have no email address for you, so we cannot send a code. The ODeL office at Akure Study Centre can add it." Body also has `centre`.
- `403 suspended` (E-06 copy).
- `429 too_many_codes` `{"retryInSeconds":1380}`: "You have asked for 5 codes in the last hour. Try again in 23 minutes, or contact your study centre."

#### `POST /auth/student/activation/resend` `{"activationId":"…"}`
Same `200` body (`codeSent:true`). `429 resend_too_soon {"retryInSeconds":42}`, `429 too_many_codes`, `410 expired` ("This took too long. Enter your matric number again.").

#### `POST /auth/student/activation/verify` `{"activationId":"…","code":"472913"}`
- `200` `{"activationToken":"opaque","expiresInSeconds":1800,"student":{"firstName":"Adeola","fullName":"Adeola Okafor","matricNumber":"WU/ODL/24/0153"}}` → M-04.
- `400 wrong_code {"attemptsLeft":2}` (M-03b): "That code is not right. 2 tries left before we send a new one."
- `400 new_code_sent {"maskedEmail":"…","resendInSeconds":60}`: "That code was wrong three times, so we sent you a new one."
- `410 code_expired`: "That code has expired. Send a new one." (also after three wrong tries when no new code could be sent).
- `410 expired` as above.

#### `POST /auth/student/activation/password` `{"activationToken":"…","password":"…","confirmPassword":"…"}` (M-04)
`204`. `400 weak_password` with `fields.password` (e.g. "Add at least one number.") or `fields.confirmPassword` ("The two passwords do not match."). `410 expired`.

#### `POST /auth/student/activation/complete` `{"activationToken":"…","acceptPrivacyNotice":true,"privacyNoticeVersion":"2026-11"}` (M-05)
Activates the account, records consent (version and time), signs the student in (cookie set) and returns the `GET /session` body.
The web then shows M-06 (`GET/PATCH /student-profile`) and goes to Home.
`400 consent_required` ("Tick the box to accept the privacy notice."), `409 password_missing` ("Create your password first."), `410 expired`.

### Sign in (M-08, E-04 … E-06)

#### `POST /auth/student/sign-in` `{"login":"WU/ODL/24/0153","password":"…","keepSignedIn":false}`
- `200` → `GET /session` body; cookie set. `keepSignedIn:true` makes the cookie survive closing the browser (until the 12-hour session limit); the 30-minute idle rule still applies.
- `401 wrong_password {"attemptsLeft":3}` (E-04): "Wrong password. 3 tries left before sign-in pauses for 15 minutes."
- `423 locked {"retryAt":"…","retryInSeconds":900}` (E-05): "Too many attempts. Sign-in is paused for 15 minutes. You can reset your password now instead." The fifth wrong password answers this directly. A correct password while locked is refused too.
- `403 suspended` (E-06): "Your account is suspended. Please contact the ODeL office at Wesley University to restore access. Your work is safe."
- `404 not_found`: "We could not find that matric number or email. Check it, or activate your account first."
- `409 not_activated`: "You have not activated your account yet. Choose First time here? Activate your account."

Sign out: `POST /auth/sign-out` (B0).

### Forgot password (M-09 … M-11)

#### `POST /auth/student/password-reset/start` `{"login":"…","codeAlreadySent":false}`
M-09 → M-10 → M-03R. Same `200` shape as activation start, with `resetId` instead of `activationId`.
`codeAlreadySent:true` (the student followed the link in an email the ODeL office sent, W-22b) reuses a live code instead of sending another.
Errors: `404 not_found`, `409 not_activated` ("This account is not active yet. Activate it first."), `422 no_email`, `403 suspended`, `429 too_many_codes`.

#### `POST /auth/student/password-reset/resend` `{"resetId"}` · `POST /auth/student/password-reset/verify` `{"resetId","code"}`
As for activation; verify returns `{"resetToken":"opaque","expiresInSeconds":1800,"student":{…}}`.

#### `POST /auth/student/password-reset/complete` `{"resetToken","password","confirmPassword"}` (M-11 "Save and sign in")
Sets the password, ends **every other session** of the student, clears a lockout, signs this browser in and returns the session body.
Same `400 weak_password` errors.

### The student's own record (M-06 "Check your details")

#### `GET /student-profile`
```json
{"id":"uuid","matricNumber":"WU/ODL/24/0153","fullName":"Adeola Okafor","firstName":"Adeola","initials":"AO",
 "programme":{"code":"BSC-CSC-ODL","name":"B.Sc. Computer Science"},"level":200,"levelLabel":"200L",
 "centre":{"code":"AKR","name":"Akure Study Centre"},"email":"adeola.okafor@gmail.com","phone":"0803 412 4417",
 "status":"ACTIVE","consent":{"version":"2026-11","at":"…"}}
```
#### `PATCH /student-profile` `{"phone":"0803 412 4417"}` → the record. Only the phone can be changed here.
The email is the one the code went to; the study centre changes only through the ODeL office (B2 move flow), so on M-06 both
are shown read-only (the design draws the centre as a dropdown: see the report).

---

## Part 2 · Import (W-18, W-18b, W-19)

### `GET /imports/students/template.csv`
The CSV template to fill (header plus one example row), `Content-Disposition: attachment; filename="students-template.csv"`.

| Column | Required | Notes |
|---|---|---|
| `matric_number` | yes | Stored in capitals, spaces removed. Format checked against the rest of the file (a row shaped unlike most rows is a warning) |
| `surname`, `first_name` | yes | `other_names` optional |
| `programme_code` | yes | A programme code from Academic structure, e.g. `BSC-CSC-ODL` |
| `level` | yes | `200`, `200L` or `Level 200`; must be a level the programme offers |
| `study_centre` | yes | The centre's code, name or short name (`AKR`, `Akure Study Centre`, `Akure`) |
| `email` | no | Needed for the welcome email and every code. Missing = warning; badly formed = error |
| `phone` | no | Contact only. Nigerian numbers; a number that is not 11 digits (or +234 and 10) is left blank with a warning |
| `gender`, `date_of_birth` | no | `M`/`F`; `YYYY-MM-DD` or `DD/MM/YYYY`. Unreadable values are left blank with a warning |
| `entry_session` | no | e.g. `2024/2025`; blank = the current session |

Header names are matched loosely (any case, spaces or underscores; `matric`, `matric_no`, `centre` also work). UTF-8 (a BOM is fine),
comma-separated, quotes as Excel writes them. Up to 20,000 rows and 10 MB. An Excel file (.xlsx) is refused with
"Save it as CSV (File → Save as → CSV UTF-8) and upload it again."

### `POST /imports/students/uploads` `{"fileName":"portal_export.csv","contentType":"text/csv","size":482113}`
`{"key":"imports/<tenant>/<uuid>.csv","uploadUrl":"…","method":"PUT","headers":{"Content-Type":"text/csv"},"expiresAt":"…"}`.
The browser PUTs the file to `uploadUrl` (relative in dev, absolute R2 in production). `400 invalid` for a non-CSV name or over 10 MB.

### `POST /imports/students/validate` `{"key":"imports/…","fileName":"portal_export.csv"}` (W-19)
Reads the uploaded file, checks every row and **saves no student**. It keeps the report (a draft import) so the confirm and the
problem list can use it.
```json
{"importId":"uuid","fileName":"portal_export.csv","status":"CHECKED","totalRows":3504,
 "ready":{"count":3478,"new":3401,"updated":77,"unchanged":0},
 "errors":{"count":26},"warnings":{"count":8},
 "problems":[{"row":14,"matricNumber":"WU/ODL/24/0201","name":"Ibrahim Musa","severity":"ERROR","field":"study_centre",
              "message":"Study centre \"Ikeja\" not found. Did you mean \"Lagos (Ikeja)\"?"}],
 "problemsShown":50,"checkedAt":"…"}
```
`row` is the spreadsheet row (the header is row 1). `problems` lists the first 50 (errors first); the rest are paged below.
Errors (row not imported until fixed): matric missing or duplicated in the file ("Matric number appears twice in this file (rows 87 and 1,204)"),
surname or first name missing, programme code unknown ("Programme code \"CSC-ODL\" unknown. Expected \"BSC-CSC-ODL\""), level missing or not offered,
study centre unknown (with a suggestion), email badly formed, email used twice in the file, email already used by another account.
Warnings (row is imported): no email (no welcome email or code until it is added), phone or date left blank, unusual matric shape,
email not changed because the student has already activated.
A matric number that is already on the platform is **not** an error: the row updates that student (names, programme, level, centre,
phone, and email while the student has not activated) and counts as `updated`; identical rows count as `unchanged`.
File-level errors: `400 file_missing` ("We could not find the uploaded file. Upload it again."), `400 not_csv`, `400 no_header`
("The first row must be the column names. Download the template to see them."), `400 missing_columns` with `fields` per missing column,
`400 too_many_rows`, `400 empty_file`.

### `GET /imports/students/{importId}` → the same report. `GET /imports/students/{importId}/problems?severity=ERROR|WARNING&page=&size=` → paged problems.
### `GET /imports/students/{importId}/problems.csv` → "Download problem rows (CSV)": the original columns plus `row`, `problem`, `type`.

### `POST /imports/students/{importId}/confirm` ("Import 3,478 valid rows")
Checks the file again (in case something changed), creates the new students and their accounts (status `IMPORTED`, accounts `INVITED`,
no email yet), updates the known ones, records the import in history and the audit log (`STUDENTS_IMPORTED`). Error rows are skipped.
```json
{"importId":"uuid","status":"IMPORTED","added":3401,"updated":77,"unchanged":0,"skipped":26,"warnings":8,
 "importedAt":"…","importedBy":{"id":"uuid","fullName":"Ngozi Adebayo"}}
```
`409 already_imported` when confirmed twice. `410 expired` when the check is more than 24 hours old ("Check the file again.").
3,504 rows import in well under a minute (measured in the e2e test).

### `GET /imports/students?page=&size=` (W-18b Import history; confirmed imports only, newest first)
`{"items":[{"id","fileName","importedAt","importedBy":{"fullName"},"totalRows","added","updated","unchanged","errors","warnings"}],…}`.
"Download latest report" = `problems.csv` of the newest item.

---

## Part 3 · Students list and record (W-22, W-22b, W-22c)

Statuses: `IMPORTED` (record exists, no welcome email yet) · `INVITED` (welcome email sent, not activated; the list shows "Not activated" for both)
· `ACTIVE` · `SUSPENDED`. Every change is kept in the record's history.

### `GET /students?q=&programme=&level=&centre=&status=&page=&size=`
`q` matches name, matric number or email (any part, any case). `programme` / `centre` are codes, `level` a number, `status` one of the statuses
(`NOT_ACTIVATED` = IMPORTED or INVITED).
```json
{"items":[{"id":"uuid","matricNumber":"WU/ODL/24/0153","fullName":"Adeola Okafor","programme":{"code":"BSC-CSC-ODL","name":"B.Sc. Computer Science"},
           "level":200,"levelLabel":"200L","centre":{"code":"AKR","name":"Akure Study Centre","shortName":"Akure"},"email":"…","status":"ACTIVE",
           "statusLabel":"Active"}],"page":0,"size":20,"total":3478}
```

### `GET /students/filters` → `{"programmes":[{"code","name","levels":[100,200]}],"centres":[{"code","name","shortName"}],"statuses":[{"key","label"}]}` (filters and the W-22c dropdowns).

### `GET /students/{id}` (W-22b)
```json
{"id","matricNumber","surname","firstName","otherNames","fullName","email","phone","gender","dateOfBirth",
 "programme":{…},"level":200,"levelLabel":"200L","centre":{…},"entrySession":"2024/2025","status":"ACTIVE","statusLabel":"Active",
 "account":{"welcomeSentAt":"…","activatedAt":"…","consent":{"version":"2026-11","at":"…"},"lastSignInAt":"…","lockedUntil":null},
 "history":[{"at","from":"INVITED","to":"ACTIVE","reason":"Activated the account","by":{"fullName":"Adeola Okafor"}}],
 "createdAt","importedFrom":{"importId","fileName"}}
```
"Activation message · Sent 11 Jan · activated 12 Jan" = `account.welcomeSentAt` / `account.activatedAt`.

### `POST /students` (W-22c Add a student) 
`{"surname","firstName","otherNames","matricNumber","programmeCode","level","centreCode","email","phone","sendWelcome":true}`
`201` → the record. Same checks as the import (`400 invalid` with `fields`; `409 matric_taken`, `409 email_taken`). `sendWelcome:true`
("Add and send welcome") sends the welcome email at once. The design has one "Full name" box; the API takes surname and first name
separately (the import file does too), so the form needs two boxes.

### `PATCH /students/{id}` (W-22b Save changes)
Any of `{"surname","firstName","otherNames","matricNumber","email","phone","programmeCode","level","centreCode"}`. Audited with old and new
values (`STUDENT_UPDATED`; a centre change is `STUDENT_CENTRE_CHANGED`). Changing the matric number or email changes what the student signs in with.
B2's "Move to another centre" flow will replace the centre edit; it works here so the office is not stuck before B2.

### `POST /students/{id}/resend-activation` → `204` (sends the welcome email again). `409 already_active`, `422 no_email`.
### `POST /students/{id}/password-reset` → `204` (emails a reset code and a link to the reset screen). `409 not_activated`, `422 no_email`.
### `POST /students/{id}/suspend` `{"reason":"Fees query"}` → the record. Signs the student out everywhere at once. `409 already_suspended`.
### `POST /students/{id}/reinstate` → the record (back to the status before the suspension).

---

## Part 4 · Welcome emails (W-20, W-20c, W-20d)

The template is owned by student-service (one per university, the platform default until edited). Fields are written `{first_name}`:
`{first_name}` `{surname}` `{full_name}` `{matric}` `{programme}` `{level}` `{centre_name}` `{centre_phone}` `{session}` `{activation_link}` `{university_name}`.
The email is sent through notification-service (key `student.welcome`), in the university's colours; links become buttons.
`{activation_link}` is `<portal>/activate?matric=WU%2FODL%2F24%2F0153` (the web pre-fills M-02 from it).

### `GET /students/welcome` (W-20)
```json
{"template":{"subject":"Welcome to {university_name} ODeL — activate your account","body":"Hello {first_name}, …","custom":false,"updatedAt":null,"updatedBy":null},
 "fields":[{"key":"first_name","label":"First name","example":"Adeola"}, …],
 "preview":{"studentName":"Adeola Okafor","subject":"…","body":"…"},
 "audience":{"notActivated":3401,"withEmail":3290,"noEmail":111,"alreadyActive":77},
 "lastSend":{"id","status":"DONE",…} }
```
`audience.notActivated` is who "Send to N students" reaches (IMPORTED or INVITED); `alreadyActive` are skipped and counted.

### `PUT /students/welcome/template` `{"subject","body"}` (W-20d Save template) → the `GET /students/welcome` body. `400 invalid` with
`fields.body` = "Unknown field {foo}. Use one of the fields below." or when `{activation_link}` is missing. Audited (`WELCOME_TEMPLATE_UPDATED`).
### `DELETE /students/welcome/template` → back to the default.
### `POST /students/welcome/test` `{"subject","body"}` (both optional: unsaved text from the editor, else the saved template)
Sends one email to the signed-in admin's own address, filled with a sample student. `200 {"sentTo":"ngozi.adebayo@…"}`.

### `POST /students/welcome/sends` `{"remindAfterDays":3}` ("Send to N students"; `remindAfterDays` null = no reminder)
Starts the background send to every not-activated student. `202`:
```json
{"id":"uuid","status":"RUNNING","startedAt":"…","finishedAt":null,"remindAfterDays":3,"reminderDueAt":"…",
 "totals":{"students":3478,"toSend":3290,"sent":1200,"failed":0,"sending":2090,"skippedNoEmail":111,"skippedTestAddress":0,"skippedActive":77},
 "progressPercent":36}
```
`409 send_running` while another send runs. The send is audited (`WELCOME_SENT`).
### `GET /students/welcome/sends/{id}` → the same body (poll every 2 seconds for the progress bar; `status` becomes `DONE`). W-20c reads
"3,290 of 3,401 emails delivered · 111 students have no email on record" from `totals`.
### `GET /students/welcome/sends?page=` → past sends.
### `GET /students/welcome/sends/{id}/deliveries?status=SENT|FAILED|SKIPPED_NO_EMAIL|SKIPPED_TEST_ADDRESS|SENDING&page=` → 
`{"items":[{"studentId","matricNumber","fullName","email","status":"FAILED","reason":"Mailbox does not exist","updatedAt"}],…}`
### `GET /students/welcome/sends/{id}/failed.csv` → "Download the failed list" (failed and skipped, with the reason).

Reminder: when `remindAfterDays` is set, students still not active that many days later get the same template once more,
subject prefixed "Reminder: ".

---

## Internal (service to service, `X-Internal-Key`, never routed by the gateway)

### student-service provides
- `GET /internal/students/summary` → `{"imported":3478,"lastImportAt":"…","welcomeSent":3290,"active":77}` (academic-service, setup checklist
  steps 8 and 9: IMPORT is done when `imported > 0`; WELCOME when `welcomeSent > 0`).
- `GET /internal/students/contact?userId=` → `{"studentId","firstName","centre":{"name","phone"}}` (identity-service, for E-03 and the code emails).
- `POST /internal/students/activated` `{"userId","activatedAt","consentVersion","consentAt"}` (identity-service, on activation).

### student-service expects from academic-service
`GET /internal/academic/lookup` (university in `X-Odel-Tenant-Id`):
```json
{"currentSession":{"id":"uuid","name":"2026/2027"},
 "programmes":[{"id":"uuid","code":"BSC-CSC-ODL","name":"B.Sc. Computer Science","levels":[100,200,300,400]}],
 "centres":[{"id":"uuid","code":"AKR","name":"Akure Study Centre","shortName":"Akure","phone":"0803 555 0117"}]}
```
Codes compared without regard to case. `levels` are the levels the programme admits students at. `shortName` and `phone` may be null;
`currentSession` may be null before step 1 is done. Until academic-service is merged, student-service uses a stub
(`ODEL_ACADEMIC_LOOKUP=stub`, the dev default in this branch) with Wesley-like data: programmes `BSC-CSC-ODL`, `BSC-IFT-ODL`
(100–400), centres `AKR` Akure Study Centre, `LAG` Lagos (Ikeja), `ABJ` Abuja, `OND` Ondo (main campus), `IBD` Ibadan, session 2026/2027.
Switch to `http` once academic-service serves the endpoint.

### identity-service provides (to student-service)
- `POST /internal/students/accounts` `{"accounts":[{"studentId","matricNumber","fullName","email","phone"}]}` → `{"accounts":[{"studentId","userId","created"}]}`;
  creates `PASSWORD` users with status `INVITED` and role `STUDENT`, or updates the one with that matric number.
- `POST /internal/students/email-conflicts` `{"entries":[{"matricNumber","email"}]}` → `{"conflicts":[{"matricNumber","email"}]}` (emails used by another account).
- `GET /internal/students/accounts/{userId}` → `{"status","lastSignInAt","lockedUntil","consentVersion","consentAt","activatedAt"}`.
- `PATCH /internal/students/accounts/{userId}` `{"matricNumber","fullName","email","phone"}`; `409 matric_taken|email_taken`.
- `POST /internal/students/accounts/{userId}/suspend` · `/reinstate` (suspend ends every session).
- `POST /internal/students/accounts/{userId}/password-reset` → emails a reset code (W-22b).

### tenant-service provides (new)
- `GET /internal/tenants/{id}/portal` → `{"portalUrl":"https://wesley.odel-dev.oyeksglobal.com"}` (for `{activation_link}`).

### notification-service provides (new)
- `POST /internal/messages/bulk` `{"messages":[<same as POST /internal/messages>]}` → `{"ids":["uuid or null", …]}` in the same order.
- `POST /internal/messages/status` `{"ids":[…]}` → `{"items":[{"id","status":"QUEUED|SENT|FAILED|SKIPPED","error","sentAt"}]}`
  (a message whose job gave up is `FAILED` with the last error).
- `GET /api/v1/dev/emails?to=` (dev only): optional filter by recipient.

## Audit actions added
student-service: `STUDENTS_IMPORTED`, `STUDENT_ADDED`, `STUDENT_UPDATED`, `STUDENT_CENTRE_CHANGED`, `STUDENT_SUSPENDED`, `STUDENT_REINSTATED`,
`WELCOME_TEMPLATE_UPDATED`, `WELCOME_TEST_SENT`, `WELCOME_SENT`, `ACTIVATION_RESENT`, `PASSWORD_RESET_SENT`.
identity-service: `ACTIVATION_CODE_SENT`, `STUDENT_ACTIVATED`, `CONSENT_RECORDED`, `SIGN_IN`, `SIGN_IN_FAILED`, `ACCOUNT_LOCKED`,
`PASSWORD_RESET_CODE_SENT`, `PASSWORD_RESET`, `SIGN_OUT`, `ACCOUNT_SUSPENDED`, `ACCOUNT_REINSTATED`.
