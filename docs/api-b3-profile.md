# API contract · B3 My profile and contact changes (L-14 … L-14j) and the ODeL office's review

The student's own profile page (web and mobile L-14) and everything it opens: change phone (L-14b, L-14c), change email with a
code sent to the new address (L-14d, L-14e), change password (L-14f), recovery codes (L-14g), photo (L-14h), ask for a name or
date-of-birth correction (L-14i), signed-in devices and "Sign out others" (L-14j). Plus what the design does not draw but a
student record needs: home address and next of kin. On the staff side: the ODeL office's review list for corrections and photos,
the student record's change history (W-22b) and the emails that tell the student about every change.

Same conventions as `docs/api-b0.md`: paths start with `/api/v1`, `X-Odel-Host` on every call, CSRF header on every non-GET,
errors `{"error","message","fields"}`, lists `{"items","page","size","total"}`, dates `YYYY-MM-DD`, times ISO-8601 (UTC; the web
shows Africa/Lagos). Ids from another university → `404`. As everywhere, **null fields are left out** of responses
(`"photo":null` in the examples below means the key is absent).

## Services and routes

| Service | Port | What it owns here | Gateway prefixes |
|---|---|---|---|
| `student-service` | 9305 | The record: phone, address, next of kin, photo, name / date of birth / gender corrections, the review list, the change history | `/api/v1/student-profile`, `/api/v1/student-profile/**` (new: the `/**` half), `/api/v1/students/**` (unchanged) |
| `identity-service` | 9301 | The login: email change and its code, password, recovery codes, signed-in devices, password reset by recovery code | `/api/v1/me/**` and `/api/v1/auth/**` (unchanged) |
| `notification-service` | 9303 | Changelog `008-profile-templates.sql` (templates below) | — |

Schema changes: student-service changelog `003-profile.sql` (new columns on `students`, tables `profile_requests` and
`profile_changes`). identity-service needs no new table: recovery codes reuse `mfa_backup_codes`, the email change lives in Redis
(changelog 004 is not used).

**Who may call:** `/student-profile/**` and `/me/email-change`, `/me/password`, `/me/recovery-codes` need the active role `STUDENT`
(staff sign in with Google and two-step, so these do not apply to them: `403 forbidden`). `/me/sessions/**` works for anyone signed in.
Staff review endpoints: `ODEL_ADMIN` (everything), `ODEL_DIRECTOR` (read only). Anyone else → `403 forbidden`.

## Who may change what

| Item | Who changes it | How |
|---|---|---|
| Phone | the student | at once; current password to confirm (L-14b). Contact only: no code (email-only decision, 24 Nov). |
| Email (also her sign-in and where codes go) | the student | password, then a 6-digit code sent to the **new** address; the old address is told (L-14d, L-14e) |
| Password | the student | current password + new one (L-14f); other browsers signed out |
| Recovery codes | the student | password; 8 new one-time codes shown once (L-14g) |
| Home address, next of kin | the student | at once, no password (not drawn: see "Not in the design") |
| Photo | the student asks, **the ODeL office approves** | upload → waiting → approved or declined (L-14h; see decisions) |
| Surname, first name, other names, date of birth, gender | the student asks with proof, **the ODeL office approves** | L-14i |
| Matric number, programme, level | the university only (W-22b, import) | "Set by the university" |
| Study centre | the ODeL office only (B2 A-06 move; the student asks through Help) | "Set by the university" |

Every change is written to the audit log and to the record's change history (`profile_changes`), and the student gets an email
(and a bell) for each one.

---

## 1. The profile page (L-14)

### `GET /student-profile`
The B1 body (M-06) plus the new fields:
```json
{"id":"uuid","matricNumber":"WU/ODL/24/0153","fullName":"Adeola Okafor","firstName":"Adeola","initials":"AO",
 "surname":"Okafor","otherNames":null,"dateOfBirth":"2003-04-17","gender":"F",
 "programme":{"code":"BSC-CSC-ODL","name":"B.Sc. Computer Science"},"level":200,"levelLabel":"200L",
 "centre":{"code":"AKR","name":"Akure Study Centre","shortName":"Akure"},
 "email":"adeola.okafor@gmail.com","maskedEmail":"adeola.o•••@gmail.com",
 "phone":"0803 412 4412","maskedPhone":"0803 *** 4412",
 "address":{"line":"14 Oyemekun Road","town":"Akure","state":"Ondo"}|null,
 "nextOfKin":{"name":"Mrs Chioma Okafor","relationship":"Mother","phone":"0805 111 2233"}|null,
 "photo":{"url":"signed link (10 minutes)","updatedAt":"…"}|null,
 "pendingPhoto":{"requestId":"uuid","url":"signed link","submittedAt":"…"}|null,
 "openRequests":[ <request, see section 4> ],
 "status":"ACTIVE","consent":{"version":"2026-11","at":"…"},
 "readOnly":{"programme":"Set by the university","centre":"Only the ODeL office can change this"}}
```
`openRequests` are the student's pending corrections and photo (so the page can show "Correction requested · waiting for the
ODeL office" on the row). The security half of the page (password changed, recovery codes left, signed-in devices) comes from
`GET /me/security` (section 3).

### `PATCH /student-profile` (L-14b "Save number", M-06, address and next of kin)
Any of:
```json
{"phone":"0816 555 2290","password":"…",
 "address":{"line":"14 Oyemekun Road","town":"Akure","state":"Ondo"},
 "nextOfKin":{"name":"Mrs Chioma Okafor","relationship":"Mother","phone":"0805 111 2233"}}
```
→ the profile. Only the parts sent change; `"address":null` / `"nextOfKin":null` clear them.
- **Phone** needs `password` (the student's current password, "To confirm it is you"), except for her **first** phone save within
  one hour of activation (M-06 "Check your details" right after the privacy notice, where she has just set it); every later change asks. Empty string clears the phone.
  Nigerian mobile numbers only, stored as `0816 555 2290`.
- `400 invalid` with `fields.phone` "Enter a Nigerian mobile number, like 0803 412 4417.", `fields.password` "Enter your password
  to confirm it is you.", `fields["address.line"]` (up to 200), `town` (up to 100), `state` (one of the 36 states and FCT, any case),
  `fields["nextOfKin.name"]` (2–150), `relationship` (up to 50), `phone` (Nigerian number).
- `401 wrong_password {"attemptsLeft":3}` "That password is not right. 3 tries left before your account pauses for 15 minutes."
  Wrong passwords here count with sign-in's (five → `423 locked`, as M-08).
- `400 no_change` "That is already your phone number."
Saved → `student.contact_changed` email (and bell) to her email: "Your phone number was changed to 0816 *** 2290 on Sat 28 Nov at
2:14 pm. If this was not you, change your password and contact the ODeL office." History rows `PHONE`, `ADDRESS`, `NEXT_OF_KIN`.

### Photo (L-14h "Change photo")
1. `POST /student-profile/uploads` `{"purpose":"PHOTO","fileName":"me.jpg","contentType":"image/jpeg","size":182000}` →
   `{"key","uploadUrl","method":"PUT","headers":{"Content-Type":"image/jpeg"},"expiresAt"}`. JPG or PNG, at most **2 MB** (the web
   crops to a square and shrinks it to 600 × 600 before uploading). `400 invalid` with `fields.fileName` "Choose a JPG or PNG
   photo." / `fields.size` "The photo is over 2 MB. Choose a smaller one.".
2. The browser PUTs the file to `uploadUrl`.
3. `POST /student-profile/photo` `{"key":"…"}` → `201` the request (kind `PHOTO`, status `PENDING`). `400 invalid`
   (`fields.key` "Upload the photo again." when the key is not hers or the file is missing / too large). A second photo while one is
   waiting replaces the waiting one (the old request becomes `WITHDRAWN`).
The current photo stays until the ODeL office approves the new one (exam slips and the attendance list show it). Approved →
`photo` changes, `student.request_approved` email + bell + push. Declined → `student.request_declined` with the reason.

---

## 2. Email change (L-14d, L-14e)

Codes follow B1's rules: 6 digits, **15 minutes**, resend after **60 seconds**, at most **5 codes an hour** (activation, reset and
email-change codes count together), **3 wrong tries** then a fresh code is sent automatically.

### `POST /me/email-change` `{"newEmail":"adeola.okafor@yahoo.com","password":"…"}` (L-14d "Send code")
- `200`:
  ```json
  {"changeId":"opaque","maskedEmail":"a•••••@yahoo.com","codeSent":true,"expiresInSeconds":900,"resendInSeconds":60,"sendsLeftThisHour":4}
  ```
  The code goes to the **new** address (`student.email_change_code`). Nothing changes yet: sign-in, receipts and codes still use
  the old address until the code is entered. Asking again for the same address within 60 seconds returns the same `changeId`
  with `codeSent:false`; another address starts over.
- `400 invalid` `fields.newEmail` "Enter an email address, like adeola@gmail.com." · `400 same_email` "That is already your email."
- `409 email_taken` "This email is already used by another account. Use a different one."
- `401 wrong_password {"attemptsLeft"}` / `423 locked` as in section 1.
- `429 too_many_codes {"retryInSeconds"}` / `429 resend_too_soon` as B1.

### `POST /me/email-change/resend` `{"changeId"}` → the same `200` body. `410 expired` "This took too long. Start again." when the
change is over 30 minutes old.

### `POST /me/email-change/verify` `{"changeId","code":"472913"}` (L-14e)
- `200 {"email":"adeola.okafor@yahoo.com","maskedEmail":"a•••••@yahoo.com","changedAt":"…"}` — done: the account signs in
  with the new address from now on, student-service's record shows it, and:
  - the **old** address gets `student.email_changed_old` ("Your email was changed to a•••••@yahoo.com … If this was not you,
    contact the ODeL office at once: your account can be locked while they check.");
  - the **new** address gets `student.email_changed` (and a bell): "From now on receipts, codes and announcements come here."
  - other browsers stay signed in (the password did not change).
- `400 wrong_code {"attemptsLeft":2}`, `400 new_code_sent {…}`, `410 code_expired`, `410 expired` (B1 wording).
- `409 email_taken` when someone took the address meanwhile.

---

## 3. Security: password, recovery codes, signed-in devices

### `GET /me/security` (the "Contact and security" card)
```json
{"passwordChangedAt":"2027-01-12T09:14:00Z",
 "recoveryCodes":{"total":8,"left":6,"createdAt":"…"}|null,
 "sessions":{"count":2,"summary":"This browser and an Android phone"}}
```
`recoveryCodes` is null until she makes some ("Make recovery codes").

### `POST /me/password` `{"currentPassword","newPassword","confirmPassword"}` (L-14f)
`204`. Same password rules as B1 (`GET /auth/student/password-rules`). Ends every **other** session (this browser stays in), clears
a lockout, emails `student.password_changed`.
`400 weak_password` with `fields.newPassword` / `fields.confirmPassword` ("The two passwords do not match."), `400 same_password`
"Your new password must be different from the current one.", `401 wrong_password {"attemptsLeft"}` (field
`currentPassword`), `423 locked`.

### `GET /me/recovery-codes` → `{"total":8,"left":6,"createdAt":"…","codes":null}`
### `POST /me/recovery-codes` `{"password"}` (L-14g "Make new codes")
`200 {"total":8,"left":8,"createdAt":"…","codes":["4821-0937", … 8 codes]}` — shown **once**; the old codes stop working.
Emails `student.recovery_codes_made`. `401 wrong_password`, `423 locked`.
A code is used on the forgot-password screen when she cannot open her email:

### `POST /auth/student/password-reset/recovery` `{"login":"WU/ODL/24/0153","recoveryCode":"4821-0937"}` (no session)
`200 {"resetToken":"opaque","expiresInSeconds":1800,"student":{…},"codesLeft":5}` → then B1's `POST /auth/student/password-reset/complete`.
The code is used up. Emails `student.recovery_code_used` to her address (so a thief is noticed).
`401 wrong_recovery_code {"attemptsLeft"}` "That recovery code is not right. 4 tries left before sign-in pauses for 15 minutes."
(counts with wrong passwords), `423 locked`, `404 not_found`, `409 not_activated`, `403 suspended`, `409 no_recovery_codes`
"You have no recovery codes. Ask the ODeL office at Akure Study Centre to check your admission letter and update your email."

### `GET /me/sessions` (L-14j)
```json
{"items":[{"id":"opaque","device":"Chrome on Windows","ip":"102.89.•.•","signedInAt":"…","current":true},
          {"id":"opaque","device":"Android phone","ip":"105.112.•.•","signedInAt":"…","current":false}],
 "summary":"This browser and an Android phone"}
```
### `POST /me/sessions/sign-out-others` → `{"signedOut":1}` ("Sign out others"). Audited `SIGN_OUT_OTHERS`.
### `DELETE /me/sessions/{id}` → `204` (one device). `404` for an unknown id or this browser (use `POST /auth/sign-out`).

---

## 4. Corrections: name, date of birth, gender (L-14i)

### `POST /student-profile/uploads` `{"purpose":"PROOF","fileName":"birth-certificate.pdf","contentType":"application/pdf","size":421888}`
As for the photo; JPG, PNG or PDF up to **5 MB** (`fields.size` "The file is over 5 MB. Scan it at a lower resolution or take a photo.").

### `POST /student-profile/corrections` (L-14i "Send request")
```json
{"field":"SURNAME","requestedValue":"Okafor","note":"My JAMB letter and birth certificate both say Okafor.",
 "proof":{"key":"…","fileName":"birth-certificate.pdf","contentType":"application/pdf","size":421888}}
```
`field` ("What is wrong"): `SURNAME` "Surname spelling", `FIRST_NAME` "First name spelling", `OTHER_NAMES` "Other names",
`DATE_OF_BIRTH` "Date of birth", `GENDER` "Gender". `requestedValue` ("Should be"): a name (letters, spaces, hyphens and
apostrophes, up to 100; other names up to 150 and may be empty), a date `YYYY-MM-DD` (age 14–100), or `M` / `F`.
`GET /student-profile/correction-fields` → `[{"key":"SURNAME","label":"Surname spelling","current":"Okafor"}, …]` (the dropdown and "On record now"; gender's `current` is "Male" / "Female").
→ `201` the request. Errors: `400 invalid` with `fields.field` "Choose what is wrong.", `fields.requestedValue`, `fields.note`
(up to 500), `fields.proof` "Add a document that shows the right spelling (proof is required)." / "Upload the file again.";
`400 no_change` "That is what we have on record now."; `409 request_pending` "You already asked to correct your surname. Wait for
the ODeL office, or withdraw that request first."
Sent → `student.request_received` email + bell to her; a bell `staff.profile_request_new` to every ODeL admin.

### The request
```json
{"id":"uuid","kind":"CORRECTION","field":"SURNAME","fieldLabel":"Surname spelling","currentValue":"Okafur","requestedValue":"Okafor",
 "note":"…","proof":{"fileName":"birth-certificate.pdf","size":421888,"sizeLabel":"412 KB","contentType":"application/pdf","type":"PDF"}|null,
 "status":"PENDING","statusLabel":"Waiting for the ODeL office","submittedAt":"…",
 "decision":{"at":"…","by":"Mrs Ngozi Adebayo","note":"…"}|null}
```
`kind` `PHOTO` has `field:null`, `currentValue:null`, `requestedValue:null`. Statuses: `PENDING`, `APPROVED`, `DECLINED`, `WITHDRAWN`.

### `GET /student-profile/requests` → `{"items":[request…]}` (newest first) · `GET /student-profile/requests/{id}`
### `GET /student-profile/requests/{id}/proof` → `{"url","expiresAt"}` (5 minutes; the photo request's file for `PHOTO`)
### `POST /student-profile/requests/{id}/withdraw` → the request (`WITHDRAWN`). `409 not_pending`.

---

## 5. The ODeL office (staff)

### `GET /students/profile-requests?status=PENDING|APPROVED|DECLINED|WITHDRAWN|ALL&kind=CORRECTION|PHOTO&q=&page=0&size=25`
Default `status=PENDING`, oldest first for pending (first come, first served), newest first otherwise. `q` matches name or matric.
```json
{"items":[{"id","kind":"CORRECTION","field":"SURNAME","fieldLabel":"Surname spelling","currentValue":"Okafur","requestedValue":"Okafor",
           "status":"PENDING","submittedAt":"…","student":{"id","matricNumber":"WU/ODL/24/0153","fullName":"Adeola Okafur",
           "centre":{"code","name","shortName"}},"hasProof":true}],
 "page":0,"size":25,"total":3,"counts":{"pending":3,"corrections":2,"photos":1}}
```
### `GET /students/profile-requests/{id}` (the "detail body")
`{"request":<the request>,"student":{"id","matricNumber","fullName","centre"},"currentPhotoUrl","newPhotoUrl"}`; the photo links
(10 minutes, side by side) only for `PHOTO`.
### `GET /students/profile-requests/{id}/proof` → `{"url","expiresAt"}`.
### `POST /students/profile-requests/{id}/approve` `{"note":null}` → the detail body above (`request.status` `APPROVED`).
Applies the change at once: the record (and the account's name in identity-service) for names; date of birth; gender; the photo.
Exam slips, results and lists use the new value from then on. `student.request_approved` email + bell + push.
### `POST /students/profile-requests/{id}/decline` `{"note":"The birth certificate photo is not readable. Upload a clearer copy."}`
→ the detail body (`request.status` `DECLINED`). Note required (5–500). `student.request_declined` email + bell + push with the note.
Errors for both: `403 forbidden` (not ODeL admin), `404`, `409 not_pending` "This request was already approved by Mrs Ngozi Adebayo."
Audited `STUDENT_PROFILE_REQUEST_APPROVED` / `_DECLINED`.

### `GET /students/{id}/profile-changes?page=&size=` (W-22b "Changes" list)
```json
{"items":[{"at":"…","field":"EMAIL","fieldLabel":"Email","oldValue":"adeola.okafor@gmail.com","newValue":"adeola.okafor@yahoo.com",
           "source":"STUDENT","sourceLabel":"By the student (code sent to the new address)","by":{"fullName":"Adeola Okafor"},"requestId":null}],…}
```
`field`: `PHONE`, `EMAIL`, `ADDRESS`, `NEXT_OF_KIN`, `PHOTO`, `SURNAME`, `FIRST_NAME`, `OTHER_NAMES`, `DATE_OF_BIRTH`, `GENDER`,
`MATRIC_NUMBER`. `source`: `STUDENT` (self-service), `REQUEST` (approved correction or photo; `by` = the approver), `OFFICE`
(W-22b edit). Next-of-kin and address values are shown as one line ("Mrs Chioma Okafor (Mother) · 0805 111 2233").
B1's `PATCH /students/{id}` now also writes these rows and emails the student when the office changes her email or phone
(`student.contact_changed`, "by the ODeL office").
`W-22b` record (`GET /students/{id}`) gains `address`, `nextOfKin`, `photo` (`{"url","updatedAt"}`, signed link) and `openRequests` (a count).

---

## Emails, bell and push (notification-service changelog 008)

| Key | Email | Bell | Push | To |
|---|---|---|---|---|
| `student.email_change_code` | ✓ | | | the new address: the code |
| `student.email_changed_old` | ✓ | | | the old address |
| `student.email_changed` | ✓ | ✓ | | the new address |
| `student.contact_changed` | ✓ | ✓ | | phone, address or next of kin changed (by her or the office) |
| `student.password_changed` | ✓ | ✓ | | |
| `student.recovery_codes_made` | ✓ | ✓ | | |
| `student.recovery_code_used` | ✓ | ✓ | | |
| `student.request_received` | ✓ | ✓ | | correction or photo sent |
| `student.request_approved` | ✓ | ✓ | ✓ | |
| `student.request_declined` | ✓ | ✓ | ✓ | |
| `staff.profile_request_new` | | ✓ | | every ODeL admin |

## Audit actions added
student-service: `STUDENT_CONTACT_CHANGED`, `STUDENT_PHOTO_REQUESTED`, `STUDENT_CORRECTION_REQUESTED`, `STUDENT_PROFILE_REQUEST_WITHDRAWN`,
`STUDENT_PROFILE_REQUEST_APPROVED`, `STUDENT_PROFILE_REQUEST_DECLINED`, `STUDENT_EMAIL_CHANGED`.
identity-service: `STUDENT_EMAIL_CHANGE_CODE_SENT`, `STUDENT_EMAIL_CHANGED`, `STUDENT_PASSWORD_CHANGED`, `STUDENT_RECOVERY_CODES_MADE`,
`STUDENT_RECOVERY_CODE_USED`, `STUDENT_RECOVERY_CODE_FAILED`, `STUDENT_PASSWORD_CHECK_FAILED`, `SIGN_OUT_OTHERS`, `SIGN_OUT_DEVICE`.

## Internal (X-Internal-Key, never routed)
- identity provides `POST /internal/students/accounts/{userId}/check-password` `{"password"}` → `204`, or `401 wrong_password` /
  `423 locked` (counts towards the lockout). Used by student-service for the phone change.
- student-service provides `POST /internal/students/by-user/{userId}/email` `{"email","oldEmail"}` → `204`: identity calls it when an
  email change is confirmed (record updated, history row `EMAIL` source `STUDENT`, audit).
- student-service uses identity's `GET /internal/staff?role=ODEL_ADMIN` (the bell for new requests) and B1's `/internal/students/accounts/{userId}/change` (names).

## Not in the design, or chosen differently (see the report)
- **Address and next of kin** are not drawn on L-14; they are added to the API (self-service). The web needs two rows on the
  "Contact and security" card (or a "Personal details" card) with a small edit dialog each.
- **Photo changes need the ODeL office's approval** (the design shows an instant change): the photo is how the invigilator
  recognises the student on the exam slip and attendance list, so letting anyone with her password swap it is an
  impersonation risk. L-14h needs a "Waiting for the ODeL office" state.
- **Recovery codes are shown once.** L-14g "View" lists the codes again; we keep only hashes (like staff backup codes), so "View"
  shows how many are left and "Make new codes" shows the new set once. They are used on the forgot-password screen (new endpoint
  above), which D1's M-09/M-10 do not draw: the web needs a "Use a recovery code" link on M-10/E-03.
- **Gender** is added to the correction dropdown (it is on the record and on the transcript).
- **No staff screen** is drawn for the review: the API follows B2's approvals queue (A-03 pattern).

## Tests
- Unit: `ProfileRulesTest` (student-service), `ProfileFormatTest` (identity-service).
- Journey: `e2e-tests/.../ProfileJourneyTest` (12 steps through the gateway; see the class comment).
