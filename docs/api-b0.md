# API contract · B0 Platform base

Later phases: B1 is `docs/api-b1-academic.md` (academic setup, People & roles, audit log) and `docs/api-b1-students.md`
(students import, welcome emails, student accounts).

The API is one Spring Boot app. All paths start with `/api/v1`. The web app (odel-web) calls it through a same-origin proxy: the browser only ever talks to the web host, and Next.js forwards `/api/*` to the API. Session and CSRF cookies are therefore first-party on the web host.

## Hosts and tenants

| Host | What it is |
|---|---|
| A university's web address, e.g. `odel.wesleyuni.edu.ng`, or `<slug>.<ODEL_BASE_DOMAIN>` | That university's portal: student web at `/`, staff web at `/staff` |
| `console.<ODEL_BASE_DOMAIN>` | Provider console (super admin) |
| Dev: `wesley.localhost:3000`, `crestfield.localhost:3000`, `console.localhost:3000` | Same, locally (`*.localhost` resolves to 127.0.0.1 in Chrome and Firefox) |

The web proxy must send the browser's host on every proxied request as **`X-Odel-Host`** (e.g. `wesley.localhost:3000`). The API resolves the tenant from it (falling back to `Host`). A host that matches nothing gets `404 {"error":"unknown_host"}` from tenant-scoped endpoints.

## Conventions

- JSON in and out. Times are ISO-8601 with offset (`2026-11-27T14:02:00+01:00`); the university's zone is Africa/Lagos.
- Errors: `{"error":"<code>","message":"<plain sentence for the screen>","fields":{"<field>":"<message>"}}` (`fields` only on 400/422).
- Lists: `{"items":[…],"page":0,"size":20,"total":57}`; query `?page=&size=`.
- **CSRF:** the API sets a readable cookie `XSRF-TOKEN`. Every non-GET request must send header `X-XSRF-TOKEN: <cookie value>`. Missing or wrong → `403 {"error":"csrf"}`.
- **Session:** HttpOnly cookie `ODEL_SESSION` (opaque, server-side in Redis). Staff idle timeout 30 minutes; any API call except `GET /session` resets the idle clock. `401 {"error":"signed_out"}` means the session is gone → go to the sign-in screen (S-02 copy when `reason=idle`).
- `403 {"error":"forbidden"}` = signed in but the active role may not do this. `404` also covers "exists, but in another university" (never reveal other tenants' data).

## Public

### `GET /public/context`
No session needed. Used by every page to brand itself before sign-in.
```json
{
  "kind": "UNIVERSITY",            // or "PLATFORM" on the console host
  "tenant": {
    "id": "uuid", "slug": "wesley", "name": "Wesley University", "shortName": "Wesley ODeL",
    "primaryColor": "#1A2368", "accentColor": "#C27808", "logoUrl": "https://…signed…" ,
    "googleDomain": "wesleyuni.edu.ng", "status": "SETTING_UP"   // SETTING_UP | LIVE | SUSPENDED
  },
  "devSignIn": false               // true only when the API runs with the dev Google stub
}
```
On the console host `tenant` is `{"name":"ODeL Platform", "primaryColor":"#1A2368", "accentColor":"#C27808", "logoUrl":null, …}`. Suspended university: `status` = `SUSPENDED`; the sign-in screen shows that the university's portal is paused and does not offer sign-in.

## Staff sign-in (D1 flow C, W-01 … W-06)

Staff (and provider) sign in with Google only. Students never use these endpoints.

### `GET /auth/google/start?returnTo=/staff`
Browser navigation (not fetch). 302 to Google (or, when `devSignIn` is true, to the API's dev stub page, which asks for an email). `returnTo` must be a path on the same host.

### Google → `GET /auth/google/callback` → `GET /auth/handoff?code=…`
Handled entirely by the API with redirects. The end of the chain is a 302 to the web host:

- success → `/auth/continue?next=<returnTo>` (session cookie is set)
- failure → `<sign-in path>?error=<code>`, where sign-in path is `/staff/sign-in` on a university host and `/sign-in` on the console, and code is one of:
  - `not_linked` — Google account is not a staff record of this university (W-02). The email is not put in the URL; the screen says "the Google account you chose".
  - `no_role` — a staff record with no role yet (W-02 variant: "Ask the ODeL office to give you a role").
  - `wrong_domain` — account is outside the university's Google domain (W-02 copy too).
  - `suspended` — the staff account is suspended.
  - `university_suspended`
  - `cancelled` — the person cancelled on Google's screen.
  - `expired` — the sign-in took too long; start again.

### `GET /session`
Does not reset the idle clock. `401` when there is no session.
```json
{
  "stage": "MFA_SETUP",            // MFA_SETUP | MFA_REQUIRED | CHOOSE_ROLE | SIGNED_IN
  "user": {"id":"uuid","fullName":"Ngozi Adebayo","email":"ngozi.adebayo@wesleyuni.edu.ng","initials":"NA"},
  "roles": [
    {"id":"uuid","role":"ODEL_ADMIN","roleLabel":"ODeL admin","scopeType":"UNIVERSITY","scopeId":null,"scopeLabel":"Directorate of Distance & E-learning"}
  ],
  "activeRoleId": "uuid or null",
  "idleTimeoutSeconds": 1800,
  "idleExpiresAt": "2026-11-27T14:32:00+01:00"
}
```
`/auth/continue` (a web page) reads this and routes:
`MFA_SETUP` → W-04, `MFA_REQUIRED` → W-03, `CHOOSE_ROLE` → W-06, `SIGNED_IN` → `next` (default `/staff`, or `/` on the console).
`CHOOSE_ROLE` happens only when the person has more than one role and has never chosen one; afterwards their last role is remembered.

Roles (`role` → `roleLabel`): `SUPER_ADMIN` Platform admin · `PLATFORM_SUPPORT` Platform support · `MANAGEMENT` University management · `ODEL_DIRECTOR` ODeL director · `ODEL_ADMIN` ODeL admin · `CENTRE_COORDINATOR` Centre coordinator · `LECTURER` Lecturer · `FACILITATOR` Facilitator · `EXAM_OFFICER` Exam officer · `HOD` Head of Department · `DEAN` Dean · `REGISTRY` Registry officer · `COUNSELLOR` Student counsellor. Scope types: `PLATFORM`, `UNIVERSITY`, `FACULTY`, `DEPARTMENT`, `CENTRE`, `COURSE`, `COURSE_GROUP`.

### `POST /auth/mfa/setup` (stage MFA_SETUP)
```json
{"secret":"JBSWY3DPEHPK3PXP","secretGrouped":"JBSW Y3DP EHPK 3PXP","otpauthUri":"otpauth://totp/Wesley%20ODeL:ngozi.adebayo%40wesleyuni.edu.ng?secret=…&issuer=Wesley%20ODeL&digits=6&period=30"}
```
Calling it again in the same sign-in returns the same secret. The web app draws the QR code from `otpauthUri`.

### `POST /auth/mfa/setup/confirm` `{"code":"123456"}`
- `200 {"backupCodes":["4827-1930", … 8 codes …], "session": <GET /session body>}` — show W-05; the codes are never shown again.
- `400 {"error":"wrong_code","attemptsLeft":2}`; the third wrong code → `401 {"error":"too_many_attempts"}` and the sign-in ends (back to W-01 with that message).

### `POST /auth/mfa/verify` `{"code":"123456","trustDevice":true}` (stage MFA_REQUIRED, W-03)
Same responses as confirm, but `200` returns just the session body. `trustDevice` sets a 30-day HttpOnly cookie `ODEL_TRUST` so this browser skips W-03 next time.

### `POST /auth/mfa/backup` `{"code":"4827-1930","trustDevice":false}`
Same as verify, with a backup code (each works once). `200` also returns `"backupCodesLeft": 7`.

### `POST /me/active-role` `{"roleAssignmentId":"uuid"}`
W-06 and the role switcher (S-03). Returns the session body. Remembered for next sign-in.

### `POST /session/keepalive`
"Stay signed in" on S-01. `204`.

### `POST /auth/sign-out`
S-04. `204`; cookie cleared. Then show S-02 (`/staff/sign-in?signedOut=1`).

## Setup checklist (W-10, role ODEL_ADMIN)

From B1 the checklist is served by academic-service from real data (same path); see `docs/api-b1-academic.md`.

### `GET /setup/checklist`
```json
{"steps":[
  {"number":1,"key":"SESSION","title":"Session and semesters","summary":"Session dates and semesters","status":"NOT_STARTED","actionLabel":"Start","href":"/staff/academic-setup/sessions"},
  {"number":2,"key":"STRUCTURE","title":"Academic structure","summary":"Faculties, departments and programmes","status":"NOT_STARTED", …},
  {"number":3,"key":"CATALOGUE","title":"Course catalogue","summary":"Courses for each programme", …},
  {"number":4,"key":"CENTRES","title":"Study centres","summary":"Where students study and sit exams", …},
  {"number":5,"key":"PEOPLE","title":"People and roles","summary":"Invite lecturers, coordinators and staff", …},
  {"number":6,"key":"STAFFING","title":"Assign staff to courses and centres","summary":"Who teaches each course and runs each centre", …},
  {"number":7,"key":"GRADING","title":"Grading rules","summary":"Default 5-point scale (30% CA / 70% exam)", …},
  {"number":8,"key":"IMPORT","title":"Import students","summary":"From the university portal CSV","status":"LOCKED","lockedReason":"Finish steps 1–7 first", …},
  {"number":9,"key":"WELCOME","title":"Send welcome messages","summary":"Email with activation link","status":"LOCKED", …}
],"done":0,"total":9}
```
Status: `NOT_STARTED | IN_PROGRESS | DONE | LOCKED`. In B0 every step is empty (the screens behind them arrive in B1); `href` targets may 404 until then.

## Notifications (bell, every signed-in user)

- `GET /notifications?page=0&size=20` → list of `{"id","type","title","body","link","createdAt","readAt"}` newest first.
- `GET /notifications/unread-count` → `{"count":3}`.
- `POST /notifications/{id}/read` → `204`. `POST /notifications/read-all` → `204`.
- `POST /push-tokens` `{"token":"<FCM web token>","platform":"WEB"}` → `204`; `DELETE /push-tokens` `{"token":"…"}` → `204`.

## Provider console (role SUPER_ADMIN, console host only)

### `GET /platform/tenants` (SA-01)
`{"items":[{"id","name","shortName","slug","webAddress","status","students":0,"centres":0,"createdAt","firstAdmin":{"fullName","email","accepted":false}}]}`. Empty platform → `items: []` (SA-01 empty state).

### `GET /platform/tenants/address-available?webAddress=odl.crestfield.edu.ng&slug=crestfield&name=Crestfield%20University`
`{"name":{"available":true},"webAddress":{"available":false,"message":"odl.crestfield.edu.ng is already used by Crestfield University"},"slug":{"available":true}}` (SA-02b). Each part is checked only when its parameter is sent. `name` compares without case and with runs of spaces as one.

### `POST /platform/logo-uploads` `{"fileName":"crest.svg","contentType":"image/svg+xml","size":20480}`
`{"key":"logos/…","uploadUrl":"https://…","method":"PUT","headers":{"Content-Type":"image/svg+xml"},"expiresAt":"…"}`. The browser PUTs the file straight to storage. Allowed: SVG or PNG, at most 2 MB.
`GET /platform/logo-uploads/preview?key=logos/…` → `{"url":"https://…signed…"}` for the SA-03 preview.

### `POST /platform/tenants` (SA-05 Create)
```json
{
  "name":"Wesley University","shortName":"Wesley ODeL","slug":"wesley","webAddress":"odel.wesleyuni.edu.ng",
  "googleDomain":"wesleyuni.edu.ng",
  "branding":{"primaryColor":"#1A2368","accentColor":"#C27808","logoKey":"logos/…"},
  "firstAdmin":{"fullName":"Ngozi Adebayo","email":"ngozi.adebayo@wesleyuni.edu.ng"}
}
```
- `201` → tenant detail (below). The first admin gets role `ODEL_ADMIN` and an invitation email; the audit trail gets "Created Wesley University; invited Ngozi Adebayo".
- `409 {"error":"web_address_taken"|"slug_taken"}`.
- `409 {"error":"name_taken","fields":{"name":"Wesley University is already on the platform (wesley). Open it from the universities list instead of adding it again."}}`: another university has this full name (case and extra spaces ignored). The same first-admin email at two universities is allowed: accounts belong to one university each.
- `422 {"error":"contrast","fields":{"primaryColor":"White text on #F5D76E is 1.6 : 1; it needs 4.5 : 1"}}` (SA-03b). The check: white text on the primary colour must reach 4.5 : 1 (WCAG relative luminance). The web app runs the same check live.
- `400` with `fields` for missing or invalid values (email must be in `googleDomain`; slug `^[a-z][a-z0-9-]{1,30}$`; `name`, `shortName` and `firstAdmin.fullName` need at least one letter, so `11111` is refused). `PATCH /platform/tenants/{id}` applies the same letter and unique-name rules to `name` and `shortName` (as `400` field errors).

Rules and payments (SA-04) are not in B0: universities get the default rules, set later by their admin.

### `GET /platform/tenants/{id}` (SA-05b, SA-01b)
```json
{"id","name","shortName","slug","webAddress","googleDomain","status","createdAt",
 "createdBy":{"fullName":"Chuka Obi"},
 "branding":{"primaryColor","accentColor","logoUrl","readabilityPassed":true},
 "firstAdmin":{"fullName","email","invitedAt","accepted":false,"acceptedAt":null},
 "nextSteps":[{"label":"Tenant created with its own data store","done":true}, {"label":"Branding applied to web, emails and slips","done":true}, {"label":"Invitation emailed to Ngozi Adebayo","done":true}, {"label":"Waiting: DNS record for odel.wesleyuni.edu.ng","done":false}, {"label":"Waiting: Ngozi Adebayo completes the setup checklist","done":false}]}
```

### `PATCH /platform/tenants/{id}` (SA-01b Edit)
Any of `{"name","shortName","branding":{"primaryColor","accentColor","logoKey"}}`; omitted fields stay. `logoKey: ""` removes the logo. Same `400`/`422 contrast` errors as create; returns the tenant detail. Audited as `TENANT_UPDATED` with old and new values.

Tenant detail also carries `platformUrl` (always live: `https://<slug>.<base domain>`) and, when the university gave its own domain,
`customDomain: {"address","status":"WAITING_DNS|LIVE","recordType":"CNAME","pointTo":"<slug>.<base domain>"}`. `portalUrl` (used in
emails) is the own domain once LIVE, else the platform address. `nextSteps` lists only facts the system knows.

### `POST /platform/tenants/{id}/resend-invitation` → `204`.

### `GET /platform/audit?tenantId=&page=` (SA-09)
`{"items":[{"id","at","actor":{"fullName","role"},"action":"TENANT_CREATED","summary":"Created Wesley University; invited Ngozi Adebayo","tenant":{"id","name"},"ip":"…"}],…}`. Includes every sign-in (`SIGN_IN`, `SIGN_IN_FAILED`, `MFA_SET_UP`, `MFA_FAILED`, `SIGN_OUT`, `ROLE_SWITCHED`) for all universities.

## Dev only

When the API runs with profile `dev`:
- `GET /public/context` says `devSignIn: true`, and `/auth/google/start` goes to a plain API page (`/api/v1/auth/dev/google`) asking for an email instead of Google.
- Seeded: provider `chuka.obi@odel.dev` (SUPER_ADMIN). After the provider creates Wesley (slug `wesley`, domain `wesleyuni.edu.ng`), `ngozi.adebayo@wesleyuni.edu.ng`.
- `GET /api/v1/dev/totp?secret=…` returns the current authenticator code.
- Emails are not sent; `GET /api/v1/dev/emails` lists the last 20.
- Files are stored on local disk. Upload and logo URLs are **relative** (`/api/v1/dev/storage/logos/…?expires=…&sig=…`): PUT/GET them through the same `/api` proxy. In production they are absolute R2 URLs, so treat `uploadUrl` and `logoUrl` as opaque URLs that may be relative or absolute.
