# ODeL Platform API

One Spring Boot app for the ODeL distance-learning platform: student web, staff web and the provider console all call it.
It is a modular monolith: one codebase and one jar, run as two processes on the server:

- **api** (`SPRING_PROFILES_ACTIVE=api`): HTTP traffic.
- **worker** (`SPRING_PROFILES_ACTIVE=worker`): background jobs (emails, push, later video and results jobs).

Packages are the modules: `tenancy`, `identity`, `audit`, `messaging`, `files`, `jobs`, `setup`, `platform` (B0); each later phase adds its own (academic, payments, courses, exams, results...).

## Run locally

```bash
docker compose up -d                                   # Postgres :5440, Redis :6390
mvn -q -DskipTests package
SPRING_PROFILES_ACTIVE=dev java -jar target/odel-api.jar   # http://localhost:8080
```

The `dev` profile: a stand-in for Google sign-in (type any email), emails logged instead of sent
(`GET /api/v1/dev/emails`), files on local disk with signed links, jobs run in the same process, and the
provider admin `chuka.obi@odel.dev` created on first start. `GET /api/v1/dev/totp?secret=…` gives the current
two-step code. The web app (odel-web) proxies `/api` here; open `console.localhost:3000` and `wesley.localhost:3000`.

Offline Maven (this machine cannot reach Maven Central): `.mvn/maven.config` lets Maven use artifacts already in `~/.m2`. Add `-o` when the network is down.

## Tests

`mvn verify` runs unit tests and the end-to-end phase tests against real Postgres and Redis (Testcontainers, Docker needed).
`PlatformBaseJourneyTest` is B0's journey: the provider creates Wesley, its first admin signs in with Google and two-step
codes and lands on the empty setup checklist, then the edge cases and tenant isolation against Crestfield.

## Database

Liquibase with plain SQL files in `src/main/resources/db/changelog/`, listed in `db.changelog-master.xml`. Never edit a
shipped file: add the next numbered one. Every table carries `tenant_id`; Hibernate's `@TenantId` filters every query by
the university in `TenantContext` (set from the browser's host), so tenant filtering lives in one place.

## Docs

- `docs/api-b0.md`: the API contract for B0.
- `docs/tech-stack.md`: what each technology is for.
- Build plan: the "Build plan" tab of the presenter's guide (B0–B12).
