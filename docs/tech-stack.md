# Technology stack and why

Same architecture as hospio-backend and coryd-backend: one Maven project with a Spring Boot service per module behind an api-gateway, one web app, managed services for the heavy parts (video, files, email, payments).

## Backend (odel-backend)

| Technology | Used for | Why this one |
|---|---|---|
| Java 21 + Spring Boot 3.3 | Each service (identity, tenant, notification; more per phase) | The developer's main language; strong transactions for results and payments |
| Spring Cloud Gateway | api-gateway: one entry point, routing, university from the host, one Swagger page | Same as Hospio and CoRyd |
| springdoc (Swagger) | API docs for every service at the gateway's /swagger-ui.html | Lets the web and mobile developers try every endpoint |
| Spring Web (MVC) | REST endpoints under `/api/v1` | Plain, well known, enough for 2,000 concurrent users on one server |
| Spring Security | Sign-in, CSRF protection, role checks | Covers every permission need in one place |
| Google sign-in (OpenID Connect, verified with Spring's JOSE library) | Staff and provider sign-in with university Google accounts | Staff need no new password (US-08) |
| Microsoft 365 sign-in (OpenID Connect against Microsoft Entra's multi-tenant `organizations` endpoint, ID tokens verified with the same JOSE library; issuer checked exactly per organisation) | Staff sign-in for universities on Microsoft 365 (`ODEL_MICROSOFT_CLIENT_ID`, `ODEL_MICROSOFT_CLIENT_SECRET`, `ODEL_MICROSOFT_REDIRECT_URI`) | Universities choose their staff sign-in (28 Nov 2026); one platform app serves them all, no new dependency |
| Emailed sign-in codes (Redis, the students' code rules) | Staff sign-in for universities on any other email provider (Zoho, own mail server) | Works with every mail system; the authenticator two-step still applies |
| Two-step codes (TOTP, built in) + 8 backup codes | Second factor for every staff account (W-03 to W-05) | Works with any authenticator app; no SMS cost |
| PostgreSQL 16 | All records | Reliable relational data for results and the audit trail |
| Liquibase (plain SQL files) | Database changes | Same tool and layout as CoRyd |
| Hibernate `@TenantId` | Keeps each university's data apart | Tenant filtering in one place, not in every query |
| Redis 7 | Sign-in sessions, one-time codes, rate limits | Fast expiry (30-minute idle sign-out), shared by api and worker |
| Postgres job queue (built in, `jobs` table in notification-service) | Emails and push now; video and results jobs later | No extra service; jobs commit with the change that caused them |
| Internal HTTP calls (`/internal/**`, shared key) | Services asking each other (create the first admin, send an invitation) | Simple and synchronous; Kafka can come in when a phase needs events |
| Brevo (SMTP) | All email | The project's email provider; email is the only outside channel (no SMS or WhatsApp) |
| Firebase Cloud Messaging | Browser push notifications | Free; works on Android Chrome and installed iPhone web apps |
| Cloudflare R2 (S3 API, signed links) | Documents: PDFs, slides, logos, assignment uploads | No download fees; files go browser to R2 directly, never through the API |
| Bunny Stream (from B3) | Lecture videos and recordings | Free encoding to 240p/360p/480p and audio, low delivery cost |
| Paystack (from B2) | Platform fee, split payments, refunds | Decided 26 Nov 2026 |
| Google Calendar / Meet / Drive APIs (from B4) | Live class links and recordings | Zero cost for phase 1 |
| Spring Boot Actuator | Health checks for the uptime monitor and deploys | Built in |
| JUnit 5 + Testcontainers | Unit tests and end-to-end phase tests on real Postgres and Redis | Tests run against the same database the server uses |
| systemd + nginx + certbot + GitHub Actions | One unit per service on the VPS; push to develop deploys the changed services with health checks and rollback | Same flow as Hospio |

## Web (odel-web)

| Technology | Used for | Why this one |
|---|---|---|
| Next.js (App Router) + React + TypeScript | Student web, staff web and provider console in one app | Server rendering keeps phone pages small and fast on 3G |
| Tailwind CSS | Styling from the D0 design tokens | Small CSS; each university's colours applied at runtime |
| Bun | Package manager and runner | Fast installs; already set up on this Mac |
| PWA (manifest + service worker) | Install to home screen, offline page, offline lectures (B3) | Web only, no app store |
| Playwright | End-to-end tests and screenshots at phone and desktop sizes | Checks the real screens against Figma |

## Deliberately not used (yet)

| Left out | Why |
|---|---|
| Kafka | Not needed yet: services call each other directly; added when a phase needs events (e.g. results published) |
| Flyway | Replaced by Liquibase to match CoRyd |
| JobRunr, Spring Session | Not needed: the Postgres queue and Redis sessions above have no extra moving parts |
| Sentry | To add when staging exists (error alerts); not reachable from this machine's network today |
| A mobile app (Expo) | Web only, responsive on every screen size |
