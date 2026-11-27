# Technology stack and why

Kept deliberately small: one backend app, one web app, managed services for the heavy parts (video, files, email, payments).

## Backend (odel-backend)

| Technology | Used for | Why this one |
|---|---|---|
| Java 21 + Spring Boot 3.3 | The whole API and the background worker | The developer's main language; strong transactions for results and payments |
| Spring Web (MVC) | REST endpoints under `/api/v1` | Plain, well known, enough for 2,000 concurrent users on one server |
| Spring Security | Sign-in, CSRF protection, role checks | Covers every permission need in one place |
| Google sign-in (OpenID Connect, verified with Spring's JOSE library) | Staff and provider sign-in with university Google accounts | Staff need no new password (US-08) |
| Two-step codes (TOTP, built in) + 8 backup codes | Second factor for every staff account (W-03 to W-05) | Works with any authenticator app; no SMS cost |
| PostgreSQL 16 | All records | Reliable relational data for results and the audit trail |
| Liquibase (plain SQL files) | Database changes | Same tool and layout as CoRyd |
| Hibernate `@TenantId` | Keeps each university's data apart | Tenant filtering in one place, not in every query |
| Redis 7 | Sign-in sessions, one-time codes, rate limits | Fast expiry (30-minute idle sign-out), shared by api and worker |
| Postgres job queue (built in, `jobs` table) | Emails, push, later video and results jobs | No extra service; jobs commit with the change that caused them |
| Brevo (SMTP) | All email | The project's email provider; email is the only outside channel (no SMS or WhatsApp) |
| Firebase Cloud Messaging | Browser push notifications | Free; works on Android Chrome and installed iPhone web apps |
| Cloudflare R2 (S3 API, signed links) | Documents: PDFs, slides, logos, assignment uploads | No download fees; files go browser to R2 directly, never through the API |
| Bunny Stream (from B3) | Lecture videos and recordings | Free encoding to 240p/360p/480p and audio, low delivery cost |
| Paystack (from B2) | Platform fee, split payments, refunds | Decided 26 Nov 2026 |
| Google Calendar / Meet / Drive APIs (from B4) | Live class links and recordings | Zero cost for phase 1 |
| Spring Boot Actuator | Health checks for the uptime monitor and deploys | Built in |
| JUnit 5 + Testcontainers | Unit tests and end-to-end phase tests on real Postgres and Redis | Tests run against the same database the server uses |
| Docker + GitHub Actions | Packaging and CI (tests on every push) | Low fixed cost; portable between VPS providers |

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
| Microservices, Kafka, an API gateway | One developer and one 4 GB server; a modular monolith can be split later if a module needs it |
| Flyway | Replaced by Liquibase to match CoRyd |
| JobRunr, Spring Session | Not needed: the Postgres queue and Redis sessions above are a few hundred lines and have no extra moving parts |
| Sentry | To add when staging exists (error alerts); not reachable from this machine's network today |
| A mobile app (Expo) | Web only, responsive on every screen size |
