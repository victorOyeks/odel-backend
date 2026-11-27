# ODeL Platform Backend

The API for the ODeL distance-learning platform. Same shape as hospio-backend and coryd-backend: one Maven
project, one Spring Boot service per module, one Postgres database (a schema per service), one api-gateway.

| Module | Port | Owns |
|---|---|---|
| `api-gateway` | 9300 | The only way in. Routes `/api/v1/**`, works out the university from the browser's host, one Swagger page for all services |
| `identity-service` | 9301 | Staff and provider accounts, roles, Google sign-in, two-step codes, sessions |
| `tenant-service` | 9302 | Universities (tenants), branding, provider console, setup checklist |
| `notification-service` | 9303 | Email (Brevo), in-app bell, browser push, templates, background job queue |
| `common` | (library) | Errors, tenancy, sessions and security, audit log, signed file links, internal calls |
| `e2e-tests` | (tests) | Each build phase's end-to-end journey, run against a running stack |

Later phases add their own services (academic, enrolment and payments, courses, live classes, exams, results...).

How the pieces talk:
- The web app proxies `/api/*` to the gateway with the browser's host in `X-Odel-Host`. The gateway asks
  tenant-service which university that is (cached for a minute) and passes `X-Odel-Tenant-Id` / `-Kind` on.
  Services listen on 127.0.0.1 only, so those headers can be trusted.
- Sessions are in Redis, so every service checks the session cookie itself.
- Services call each other on `/internal/**` with the shared `X-Internal-Key`; the gateway never routes `/internal`.
- Each table carries `tenant_id`; Hibernate `@TenantId` filters every query by the current university.
- One audit log, `app.audit_log`, written by every service.

## Run locally

```bash
docker compose up -d                 # Postgres :5440 (schemas created on first start), Redis :6390
mvn -q -DskipTests install
scripts/dev/run-all.sh               # every service with the dev profile; logs in target/dev-logs/
scripts/dev/stop-all.sh
```

Gateway: http://localhost:9300. Swagger for every service: http://localhost:9300/swagger-ui.html.
The `dev` profile: a typed-email stand-in for Google sign-in, emails logged (`GET /api/v1/dev/emails`), files on
local disk with signed links, and the provider admin `chuka.obi@odel.dev` created on first start.
`GET /api/v1/dev/totp?secret=…` gives the current two-step code.

Offline Maven (this machine cannot reach Maven Central): `.mvn/maven.config` lets Maven use what is in `~/.m2`; add `-o`.

## Tests

`mvn verify` runs each module's unit tests. The B0 journey (provider creates a university, its first admin signs in
with two-step codes and lands on the empty setup checklist, edge cases, tenant isolation) runs through the gateway:

```bash
ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test
```

## Database

Liquibase, plain SQL files in each service's `src/main/resources/db/changelog/`, listed in `db.changelog-master.xml`.
Never edit a shipped file: add the next numbered one. `common/.../000-app.sql` (the shared audit log) is included by every service.

## Deploying (dev)

Push `develop`: `.github/workflows/deploy-dev.yml` builds the changed services, copies the jars to the dev VPS and restarts
them in order (tenant, identity, notification, gateway) with a health check and rollback. One-time box set-up:
`scripts/vps/bootstrap-dev.sh` (database, schemas, `/opt/odel-dev/.env.dev`, systemd units, nginx, certificates).

| What | Where |
|---|---|
| Jars | `/opt/odel-dev/<service>/app.jar` |
| Shared env | `/opt/odel-dev/.env.dev` (chmod 600) |
| Units | `odel-<service>.service`, `odel-web-dev.service` |
| Web | `~/apps/odel-web-dev/current` (node server.js on :3100), deployed by odel-web's workflow |
| Hosts | `odel-dev.oyeksglobal.com` (Wesley portal), `odel-console-dev.oyeksglobal.com` (provider console), `odel-api-dev.oyeksglobal.com` (gateway, Swagger) |

## Docs

- `docs/api-b0.md`: the API contract for B0.
- `docs/tech-stack.md`: what each technology is for.
- Build plan: the "Build plan" tab of the presenter's guide (B0–B12).
