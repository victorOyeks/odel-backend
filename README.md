# ODeL Platform Backend

The API for the ODeL distance-learning platform. Same shape as hospio-backend and coryd-backend: one Maven
project, one Spring Boot service per module, one Postgres database (a schema per service), one api-gateway.

| Module | Port | Owns |
|---|---|---|
| `api-gateway` | 9300 | The only way in. Routes `/api/v1/**`, works out the university from the browser's host, one Swagger page for all services |
| `identity-service` | 9301 | Staff, student and provider accounts, roles, staff sign-in (Google, Microsoft 365 or emailed code), two-step codes, sessions, student activation, password sign-in and reset, People & roles, the university's audit log |
| `tenant-service` | 9302 | Universities (tenants), branding, provider console |
| `notification-service` | 9303 | Email (Brevo), in-app bell, browser push, templates, background job queue |
| `academic-service` | 9304 | Sessions and semesters, faculties → departments → programmes, course catalogue, study centres and rooms, staffing, versioned grading rules, structure and catalogue CSV imports, the setup checklist (schema `academic`) |
| `student-service` | 9305 | Student records and history, portal CSV import (row-by-row check, then confirm), welcome emails and delivery report, students list and record, moving a student to another centre (schema `student`) |
| `payment-service` | 9306 | The platform fee per student per semester: Paystack (hosted checkout, USSD and transfer by the Charge API) behind a `PaymentGateway` interface with a fake for local work, subaccount split (₦12,000 provider / ₦3,000 centre), signed and idempotent webhook, 30-minute re-check, automatic refund of a second payment, reversals, PDF receipts, the university's payments list and the provider's settlement report (schema `payment`) |
| `registration-service` | 9307 | Course registration per semester: window, compulsory / elective / carry-over courses, unit limits, the fee block (asks payment-service), PDF slip, late registration and course changes decided by the centre coordinator, the registrations report and rules (schema `registration`) |
| `content-service` | 9308 | Teach and learn: a course per semester (offering), weeks that open on a date, ordered items (videos on Bunny Stream behind a `VideoGateway` with a fake for local work, documents in storage, slides and notes converted to PDF by LibreOffice, links, text pages), signed playback per quality with sizes and an audio-only file, progress per student, the lecturer's progress table, announcements, copying last session's course (schema `content`) |
| `common` | (library) | Errors, tenancy, sessions and security, audit log, signed file links, internal calls |
| `e2e-tests` | (tests) | Each build phase's end-to-end journey, run against a running stack |

Later phases add their own services (enrolment and payments, courses, live classes, exams, results...).

How the pieces talk:
- The web app proxies `/api/*` to the gateway with the browser's host in `X-Odel-Host`. The gateway asks
  tenant-service which university that is (cached for a minute) and passes `X-Odel-Tenant-Id` / `-Kind` on.
  Services listen on 127.0.0.1 only, so those headers can be trusted.
- Sessions are in Redis, so every service checks the session cookie itself.
- Services call each other on `/internal/**` with the shared `X-Internal-Key`; the gateway never routes `/internal`.
- Each table carries `tenant_id`; Hibernate `@TenantId` filters every query by the current university.
- One audit log, `app.audit_log`, written by every service.
- Lookups by id go through a repository query (`findInTenant`, or a `findById` overridden with `@Query`): Hibernate's
  `find()` by id skips the `@TenantId` filter, so the inherited `findById` must not be used with an id from a request.

## Code layout

Every service has the same layered packages (as in hospio-backend), the Application class at the root:

```
com.odel.<service>
├── <Service>Application.java   scans com.odel (common included); entities and repositories under it
├── controller                  public endpoints (/api/v1/**), dev-only ones guarded by @ConditionalOnProperty
│   └── internal                /internal/** endpoints for other services
├── service                     business logic (notification-service: service/jobs for the background job queue)
├── repository                  Spring Data repositories
├── entity                      JPA entities
├── dto                         request and response records, grouped per area (<Area>Dtos)
├── client                      calls to other services and outside providers (Google, Brevo, web push)
├── config                      start-up and configuration beans
└── devseed                     dev-only seed data, when a service has any
```

`api-gateway` has `filter` (tenant header, access log) and `config` (service-down handler). `common` keeps its own
sub-packages (audit, files, instrumentation, internal, security, tenancy). Tests mirror the main packages.

## Run locally

```bash
docker compose up -d                 # Postgres :5440 (schemas created on first start), Redis :6390
mvn -q -DskipTests install
scripts/dev/run-all.sh               # every service with the dev profile; logs in target/dev-logs/
scripts/dev/stop-all.sh
```

Gateway: http://localhost:9300. Swagger for every service: http://localhost:9300/swagger-ui.html.
The `dev` profile: a typed-email stand-in for Google and Microsoft sign-in, emails logged (`GET /api/v1/dev/emails`), files on
local disk with signed links, and the provider admin `chuka.obi@odel.dev` created on first start.
`GET /api/v1/dev/totp?secret=…` gives the current two-step code.

Payments run with the fake gateway locally (`ODEL_PAYSTACK_MODE=fake`, the default): its checkout page is
`/api/v1/dev/payments/checkout/{reference}` and `POST /api/v1/dev/payments/{reference}/simulate` plays any Paystack outcome with a signed
webhook. With `ODEL_PAYSTACK_MODE=paystack` and `ODEL_PAYSTACK_SECRET_KEY` (environment only) it talks to api.paystack.co; Paystack's
webhook URL is `https://<api host>/api/v1/payments/webhooks/paystack`.

Videos run with the fake host locally (`ODEL_VIDEO_MODE=fake`, the default): an upload goes to `PUT /api/v1/dev/content/videos/{id}/upload`, the
video becomes ready on its own after `ODEL_FAKE_VIDEO_READY_SECONDS` (20) or when `POST /api/v1/dev/content/videos/{id}/simulate` says so (READY,
ENCODING, FAILED), and `POST /api/v1/dev/content/jobs/run` runs the conversion and audio jobs now. With `ODEL_VIDEO_MODE=bunny` and the
`ODEL_BUNNY_*` keys (environment only) the browser uploads straight to Bunny Stream by TUS; Bunny's webhook URL is
`https://<api host>/api/v1/content/webhooks/bunny`. Slides become PDFs only where LibreOffice (`soffice`) is installed; elsewhere they are
kept as the original file. The audio-only file needs `ffmpeg`.

Offline Maven (this machine cannot reach Maven Central): `.mvn/maven.config` lets Maven use what is in `~/.m2`; add `-o`.

## Tests

`mvn verify` runs each module's unit tests. The journeys run through the gateway of a running stack (every service):
`PlatformBaseJourneyTest` (B0: provider creates a university, its first admin signs in with two-step codes),
`AcademicSetupJourneyTest` (B1: W-10 set-up, People & roles, audit log), `StudentsInJourneyTest` (B1: import, welcome
emails, student activation, sign-in and reset, the 3,504-row timing; it builds its programmes and centres through academic-service),
`PaymentsJourneyTest` (B2 payments, fake gateway: fee cards, card declined and closed, paid by webhook before and after the browser returns,
the same webhook twice, transfer confirmed later, USSD and the 30-minute re-check, paid twice and refunded, reversal, reports and the split)
and `RegistrationJourneyTest` (B2: course registration, slip, late and change requests, coordinator scope, A-07, the centre move; it sets
each student's fee answer through the dev-only `PUT /api/v1/dev/registration/fee-status`) and `ContentJourneyTest` (B3: Dr Bello builds CSC 201 week 5
with a video, notes, slides, a link and a page; the video is prepared by the fake Bunny host and its webhook, a second one fails and is uploaded again;
Adeola watches at 360p, switches to audio, reads the slides and finishes the week; Dr Bello's progress table, the coordinator's centre-only view,
the announcement by bell and email, the item menu, copying last session's course, and every refusal).

```bash
ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test -Dtest=PlatformBaseJourneyTest
```

Run them one at a time: each sets up the provider's two-step afresh, so between runs clear the provider's two-step and
the `odel:totp-used:*` keys in Redis (a code's time step is refused for two minutes after it is used).

## Database

Liquibase, plain SQL files in each service's `src/main/resources/db/changelog/`, listed in `db.changelog-master.xml`.
Never edit a shipped file: add the next numbered one. `common/.../000-app.sql` (the shared audit log) is included by every service.

## Deploying (dev)

Push `develop`: `.github/workflows/deploy-dev.yml` builds the changed services, copies the jars to the dev VPS and restarts
them in order (tenant, identity, academic, student, payment, registration, content, notification, gateway) with a health check and rollback. One-time box set-up:
`scripts/vps/bootstrap-dev.sh` (database, schemas, `/opt/odel-dev/.env.dev`, systemd units, nginx, certificates).

| What | Where |
|---|---|
| Jars | `/opt/odel-dev/<service>/app.jar` |
| Shared env | `/opt/odel-dev/.env.dev` (chmod 600) |
| Units | `odel-<service>.service`, `odel-web-dev.service` |
| Web | `~/apps/odel-web-dev/current` (node server.js on :3100), deployed by odel-web's workflow |
| Hosts | `odel-dev.oyeksglobal.com` (Wesley portal), `odel-console-dev.oyeksglobal.com` (provider console), `odel-api-dev.oyeksglobal.com` (gateway, Swagger), and every university at `<slug>.odel-dev.oyeksglobal.com` |
| Wildcard | DNS `*.odel-dev.oyeksglobal.com` (Cloudflare) and certificate `odel-dev-wildcard`, issued by DNS-01 with `scripts/vps/certbot/cloudflare-*.sh` (token in `/root/.secrets/cloudflare.ini`, IPv4 only); nginx site `nginx/odel-dev-wildcard` |

## Docs

- `docs/api-b0.md`: the API contract for B0.
- `docs/api-b1-academic.md`: B1 academic setup (sessions, structure, catalogue, centres, staffing, grading, checklist, People & roles, audit log).
- `docs/api-b1-students.md`: B1 students (import, welcome emails, student activation, sign-in and reset).
- `docs/api-b2-payments.md`: B2 payments (fees per semester, Paystack, webhook, receipts, reports, settlement accounts).
- `docs/api-b2-registration.md`: B2 course registration (window, courses, slip, late and change requests, approvals, report, centre move).
- `docs/api-b3-content.md`: B3 teach and learn (courses, weeks, items, uploads to Bunny Stream and storage, playback, progress, announcements, copy).
- `docs/tech-stack.md`: what each technology is for.
- Build plan: the "Build plan" tab of the presenter's guide (B0–B12).
