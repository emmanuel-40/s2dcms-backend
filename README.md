# S2DCMS Backend

**Student to Department Complaint Management System (S2DCMS)** - a full-stack complaint platform where students can register, file complaints with attachments, track status, and message departments; departments can view, reply to, and close complaints; and administrators manage departments and users. This repository is the Spring Boot REST API backend; the React frontend lives in [s2dcms-frontend](https://github.com/emmanuel-40/s2dcms-frontend).

**Live API:** https://s2dcms-backend.onrender.com  
**Frontend:** https://student-complaints-tau.vercel.app

## What each role can do

| Role | What they can do |
|------|------------------|
| **Student** | Register & log in, update profile, upload profile picture, submit complaints with attachments, view complaint status, message departments |
| **Department** | View student complaints, open complaint details, reply to students, track seen/unread messages, manage department profile, close complaints |
| **Admin** | Create/delete/update departments, view all students, delete students, role-based access control on admin-only endpoints |

## Related Repositories

- **Frontend**: [s2dcms-frontend](https://github.com/emmanuel-40/s2dcms-frontend) - React frontend with Vite and Tailwind CSS
- **API reference**: [API_DOCUMENTATION.md](API_DOCUMENTATION.md) - request/response contract for every endpoint
- **Runbook**: [RUNBOOK.md](RUNBOOK.md) - operational playbook for deploys, incidents, and on-call

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file in this repository for details.

## Overview

This backend provides REST APIs for student and department authentication, complaint management, file uploads, and messaging with JWT-based security, Redis caching, and RabbitMQ email processing.

## System Architecture

```mermaid
graph TB
    subgraph "Client Layer"
        Client[Web Browser<br/>Vercel SPA<br/>https://student-complaints-tau.vercel.app]
    end
    
    subgraph "Backend - Render"
        API[Spring Boot API<br/>https://s2dcms-backend.onrender.com<br/>Java 17 / Spring Boot 3.3]
        
        subgraph "Security Layer"
            Security[Spring Security<br/>JWT + CSRF + RBAC<br/>HttpOnly Cookies]
            Idempotency[IdempotencyInterceptor<br/>Redis Lua Scripts<br/>SHA-256 Body Hash]
            RateLimit[Rate Limiter<br/>PostgreSQL<br/>4 attempts / 60 min]
        end
        
        subgraph "Business Layer"
            Services[Service Layer<br/>AuthService, StudentService<br/>DepartmentService, EmailService]
            Cache[Redis Cache<br/>Read-Through<br/>10 min TTL]
        end
        
        subgraph "Data Access Layer"
            JPA[Spring Data JPA<br/>Hibernate + Flyway<br/>16 Migrations]
        end
    end
    
    subgraph "Data Layer"
        DB[(PostgreSQL<br/>Supabase Port 6543<br/>Transaction Pooler<br/>HikariCP Max 5)]
        Redis[(Redis Cloud<br/>Session + Cache<br/>Lettuce Pool)]
        Queue[RabbitMQ<br/>CloudAMQP<br/>Email Queue<br/>Dead Letter Queue]
    end
    
    subgraph "External Services"
        Email[Brevo Email API<br/>Verification + Reset<br/>Circuit Breaker]
        AI[Groq AI<br/>Complaint Summarization<br/>Reply Suggestions<br/>Circuit Breaker]
        Storage[Supabase Storage<br/>File Uploads<br/>5MB Limit]
    end
    
    subgraph "Monitoring & Observability"
        Grafana[Grafana Cloud<br/>Prometheus Metrics<br/>P95 Latency Tracking]
        Actuator[Spring Boot Actuator<br/>/health, /metrics<br/>/prometheus]
        K6[K6 Load Tests<br/>On-demand, Read-only<br/>Threshold-gated]
        Uptime[UptimeRobot<br/>Health Checks<br/>Every 5 min]
    end
    
    subgraph "DevOps"
        CI[GitHub Actions CI<br/>test gates deploy<br/>load-test manual<br/>PostgreSQL + Redis + RabbitMQ]
        Docker[Docker<br/>Multi-stage Build<br/>Maven → JRE]
    end
    
    Client -->|HTTPS + HttpOnly Cookies| API
    API --> Security
    Security --> Idempotency
    Security --> RateLimit
    Idempotency --> Services
    Services --> Cache
    Services --> JPA
    JPA --> DB
    Services --> Redis
    Services --> Queue
    Queue --> Email
    Services --> AI
    Services --> Storage
    API --> Actuator
    Actuator --> Grafana
    K6 -->|manual run vs live target| API
    Uptime --> API
    CI --> API
    Docker --> API
    
    style API fill:#4CAF50,color:#fff
    style Security fill:#2196F3,color:#fff
    style DB fill:#FF9800,color:#fff
    style Redis fill:#E91E63,color:#fff
    style Queue fill:#9C27B0,color:#fff
    style Grafana fill:#00BCD4,color:#fff
    style CI fill:#607D8B,color:#fff
```

## Project at a Glance

| Area | Detail |
| --- | --- |
| **Live API** | https://s2dcms-backend.onrender.com (`/health`, `/health/redis`) |
| **Frontend** | https://student-complaints-tau.vercel.app |
| **API surface** | 37 documented endpoints across 6 controllers (student, department/admin, auth, AI, contact, health) |
| **Codebase** | 89 Java classes - 13 services, 6 controllers, 6 repositories, 13 security/config classes |
| **Data layer** | PostgreSQL, 16 Flyway migrations, Redis cache, RabbitMQ e-mail queue |
| **Auth model** | HttpOnly-cookie JWT, refresh-token rotation, 4-session cap, 3 role-based access policies |
| **Quality** | 60 automated tests (integration through the real security filter chain + unit) |
| **API reference** | [API_DOCUMENTATION.md](API_DOCUMENTATION.md) - per-endpoint request/response contracts |
| **Container** | Multi-stage `Dockerfile` (Maven build -> Temurin JRE runtime) |


# Features

## Authentication & Security
- **Cookie-Based Authentication**: Enterprise-grade security using HttpOnly cookies (no localStorage tokens)
- **CSRF Protection**: Spring Security CSRF tokens with X-XSRF-TOKEN headers (prevents cross-site request forgery)
- **Refresh Token Rotation**: Advanced token management with automatic rotation - maintains maximum 4 active sessions per user, automatically invalidating oldest tokens when new sessions are created
- **Environment-Aware Security**: Automatic detection of development vs production environments for optimal cookie settings
- **SameSite Cookie Configuration**: Lax for development, None for production (cross-origin support)
- **Security Headers**: HSTS, frameOptions, CSP for comprehensive protection
- **Automated Token Cleanup**: Scheduled job every 5 hours deletes expired and revoked tokens to keep the session table small
- **Role-Based Authorization**: Granular access control with STUDENT, DEPARTMENT, and ADMIN roles
- **Secure Password Hashing**: BCrypt encryption for secure password storage
- **Rate Limiting Protection**: Persistent PostgreSQL attempt-counter per email + action (default 4 attempts, 60-minute cooldown), reset on successful login, with error messages that report the remaining cooldown

## Student Features
- Register & Login
- Update Profile
- Upload Profile Picture
- Submit Complaints
- Upload Complaint Attachments
- View Complaint Status
- Messaging System

## Department Features
- View Student Complaints
- Open Complaint Details
- Reply to Students
- Track Seen/Unread Messages
- Department Profile Management
- Close Complaints
- **AI-Powered Features**:
  - Complaint Summarization: Auto-generate bullet point summaries
  - Reply Suggestions: Get AI-suggested professional responses
  - Complaint Writing Assistant: Help students write formal complaints

## Admin Features
- **Department Management**:
  - Create new departments
  - Delete departments
  - Update department passwords
- **Student Management**:
  - View all students
  - Delete students
- **Role-Based Access**: Admin-only endpoints protected by role-based authorization

## Contact Features
- Public Contact Form
- Contact Message Management

## File Upload System
- Profile Image Upload
- Complaint Attachments
- File Validation
- File Size Restrictions (5MB limit)
- MIME Type Validation
- Automatic File Cleanup: Old files are automatically deleted when updating profile pictures or department replies
- Secure Storage: Organized directory structure with proper file naming

## Performance & Optimization
- **Redis Read-Through Caching**: `@Cacheable` on profile reads and `@CacheEvict` on writes (`studentProfile`, `departmentProfile`, `messagesByStudent`, `messageDetailsStudent`, `messageDetailsDept`, `DepartmentMessages`) with a 10-minute TTL and JSON-serialised values
- **Cache Failure Isolation**: a custom `CacheErrorHandler` (`CacheErrorConfig`) downgrades cache errors to warnings, so a Redis outage or stale serialised entry falls through to PostgreSQL instead of failing the request
- **Redis Health Monitoring**: Automated PING every 5 minutes to prevent database deletion (RedisHealthScheduler)
- **Database Indexing**: Optimized database queries with strategic indexing on frequently accessed columns
- **Pagination**: Efficient data retrieval with server-side pagination
- **Lazy Loading**: Optimized entity loading to reduce database queries
- **DTO-based Responses**: Clean separation between internal models and API contracts

## Database & Migration
- PostgreSQL
- Flyway Migration
- JPA / Hibernate

## Complaint Submission Retry

When a student submits a complaint and the backend returns `503` (service temporarily unavailable - usually because the notification worker is still warming up on Render's free tier), the frontend retries up to **3 times**. It stops immediately on `200`, `409`, or `400` - those are definitive answers and retrying would not help.

During the retry window the user sees:

> Retrying your submission. The notification service is warming up, so this will take only a moment.

This keeps the user informed instead of showing a bare error while the free-tier backend wakes up.

---

# Tech Stack

- **Java 17+** - Programming language
- **Spring Boot 3.x** - Application framework
- **Spring Security** - Security framework
- **JWT (jjwt)** - Token-based authentication
- **Spring Data JPA** - Database ORM
- **Hibernate** - JPA implementation
- **PostgreSQL** - Primary database
- **Redis** - Caching and session storage
- **RabbitMQ** - Message queue for email processing
- **Flyway** - Database migration
- **Brevo API** - Email service
- **Groq AI** - AI-powered complaint summarization and reply suggestions
- **Maven** - Build tool

## Reliability & Auto-Retry

Every outgoing call that can fail transiently uses **exponential backoff with jitter** instead of fixed or linear waits. Jitter (0 300 ms random) prevents a thundering herd when a dependent service recovers.

### Retry layers

| Layer | Trigger | Max attempts | Per-attempt timeout | Backoff formula | Non-retryable (fail fast) |
|-------|---------|--------------|---------------------|-----------------|---------------------------|
| **Email consumer** (`EmailConsumerService`) | Brevo 5xx / 429 / unexpected error | 3 | **10 s connect + 10 s read** (OkHttp, `BrevoConfig`) | `500 ms  2^n + 0 300 ms jitter` | Brevo 4xx client errors |
| **Groq AI** (`AIComplaintService`) | Groq 5xx / network / unexpected | 3 | **3 s connect + 8 s read** (`application.properties`) | `500 ms  2^n + 0 300 ms jitter` | Groq 4xx (non-429) |
| **Groq AI rate-limit** (`AIComplaintService`) | Groq 429 | 3 | **3 s connect + 8 s read**, plus honors `Retry-After` header; **30 s default** if absent | `500 ms  2^n + 0 300 ms jitter` |  |
| **Frontend complaint submit** (`NewComplaint.jsx`) | HTTP 503 | 3 | **30 s AbortController** (`apiClient.js`) | `500 ms  2^n + 0 300 ms jitter` | HTTP 200 / 400 / 409 |

### Timing example (3 attempts)

Only **2 delays** occur between the 3 attempts (no wait after attempt 3). Per-attempt timeouts dominate the worst case:

- Attempt 1 fails ? wait **500 800 ms** ? Attempt 2
- Attempt 2 fails ? wait **1000 1300 ms** ? Attempt 3
- Attempt 3 fails ? give up immediately

**Worst-case Groq AI call: ~35 seconds** (3  11 s per attempt + ~2.1 s retry delays).
**Worst-case Brevo email call: ~62 seconds** (3  20 s per attempt + ~2.1 s retry delays)  runs on a RabbitMQ consumer thread, **not** the client-facing request thread.
**Frontend abort: 30 seconds** if the backend hasn't responded by then, the tab aborts with a clear timeout message.

The previous configuration had no explicit timeout on Brevo (unbounded hang) and no frontend timeout (relied on browser default). All layers now fail fast instead of hanging indefinitely.

### Email idempotency (dedup)

`EmailConsumerService` writes `email:dedup:<to>:<type>:<token\|message>` to Redis via atomic `SET IF ABSENT` with a **1-hour TTL** before calling Brevo. If RabbitMQ redelivers the same message after a consumer crash (post-send, pre-ack), the second delivery sees the key already present and skips sending eliminating duplicate emails on redelivery. This complements the existing `IdempotencyInterceptor` (Redis `SET IF ABSENT`, 24 h TTL, HTTP 409 on duplicate `Idempotency-Key`) which protects all `/api/**` mutating endpoints including complaint submission.
- **Docker** - Container-ready via a multi-stage `Dockerfile` (`maven:3.9-eclipse-temurin-17` build stage -> `eclipse-temurin:17-jre` runtime stage)


# Project Status

This project is currently under active development.

Completed:
- Authentication System
- Role-Based Access Control
- Complaint Management
- File Uploads
- Redis Caching
- Profile Management
- Department Messaging
- AI-Powered Features (Groq integration)


## Testing & Quality

Authentication, session handling, and AI failure handling are covered by an automated suite. Integration tests run against a real Spring context, including the real security filter chain - the security behaviour is tested as it is deployed, not as a mock.

bash
cd S2dcms-backend
mvn test        # surefire prints the per-class and total counts


> Per-class counts are deliberately not quoted here: the last run is the only source of truth, and a
> number copied into a README goes stale the first time anyone adds a test.

### 1. `AuthControllerIntegrationTest` - integration (MockMvc through the real filter chain)

`@SpringBootTest` + `MockMvc` drives the full `SecurityFilterChain` - JWT cookie filter -> CSRF -> authorization - against an H2 database. What it verifies, in request order:

1. **Login** - both `STUDENT` and `DEPARTMENT` accounts authenticate and receive cookies
2. **Cookie attributes** - `HttpOnly`, `SameSite`, `Path` and `Max-Age` are asserted on every `Set-Cookie`
3. **Response body** - contains only `email` and `role`; no token is ever serialised to the client
4. **Header refusal** - an `Authorization: Bearer ...` request is rejected; cookies are the only accepted credential
5. **CSRF** - required on unsafe methods, skipped for safe methods; a missing token on an authenticated request is `403` with an actionable message (never `401`), and `GET /api/auth/csrf` serves the token to cross-origin SPAs as JSON
6. **Refresh** - the token rotates and the previous token is revoked
7. **Tampering** - modified, unknown and expired cookies are all rejected
8. **Logout** - cookies are cleared and the session is revoked server-side
9. **Password change** - every existing refresh token is invalidated
10. **Rate limiting** - repeated failures lock further attempts for the cooldown window

### 2. `AiProviderExceptionTest` - unit

The AI endpoints promise a status a client can act on without ever repeating what the provider said, so both halves of that promise are asserted directly: provider `429` becomes `429` carrying the upstream `Retry-After` (falling back to 30s when it is missing, unparsable, or absurd), outages and connectivity failures become `503`, a rejected payload becomes `502`, an already-classified error survives being rethrown, and a broken API key on our side surfaces as `503` with no key/provider/quota wording in the body.

### 3. `AuthServiceTest` - unit

Mockito with mocked repositories: verifies authentication decisions and their side effects in isolation from the web and persistence layers.

### 4. `IdempotencyUnitTest` - unit

Mockito with mocked `RedisTemplate` and `ObjectMapper`; calls `IdempotencyInterceptor.preHandle()` directly (no Spring context). Covers the full state machine: missing key, new key, duplicate key with body-hash match (response replay), duplicate key with body-hash mismatch (409), invalid key format, key over 256 chars, same key for different users/endpoints, Redis returning null, Redis throwing exceptions (fail-safe), `FAILED` state allowing retry, concurrent `PROCESSING` requests (409), and stale `PROCESSING` reclamation.

### 5. `MultipartUploadConfigTest` - integration

Asserts the servlet upload ceiling is the configured 5MB and not Spring Boot's 1MB default, and that the request ceiling is never below the file ceiling. This is a regression test for a production-only failure: the limit lived in a git-ignored properties file, so it was correct locally and 1MB in production.

### 6. `S2dcmsApplicationTests` - smoke

Spring context load: confirms the application boots with the current configuration.

---

Test names describe observable behaviour rather than implementation detail, so the suite doubles as the executable specification for the auth design.

# Setup Instructions

## Clone Repository

bash
git clone https://github.com/emmanuel-40/s2dcms-backend.git
cd s2dcms-backend


---

## Quick Start with Docker Compose (Recommended)

The fastest way to get started - Docker Compose will set up PostgreSQL, Redis, RabbitMQ, and the backend for you.

### Prerequisites

- Docker Desktop installed and running
- Git

### Setup Steps

1. **Create environment file:**
   ```bash
   cp .env.example .env
   ```

2. **Edit `.env` with your credentials:**
   - `POSTGRES_USER` - PostgreSQL username (default: postgres)
   - `POSTGRES_PASSWORD` - Set a secure PostgreSQL password
   - `POSTGRES_DB` - Database name (default: s2dcmsdb)
   - `BREVO_API_KEY` - Your Brevo API key (required for email functionality)
   - `FRONTEND_BASE_URL` - Your frontend URL (default: `http://localhost:5173` for local development)
   - `ENVIRONMENT` - Environment (default: development)

   Example `.env`:
   ```bash
   POSTGRES_USER=postgres
   POSTGRES_PASSWORD=my_secure_password
   POSTGRES_DB=s2dcmsdb
   BREVO_API_KEY=xkeysib-abc123...
   FRONTEND_BASE_URL=http://localhost:5173
   ENVIRONMENT=development
   ```

3. **Start all services:**
   ```bash
   docker-compose up -d
   ```

   This starts:
   - PostgreSQL (port 5432)
   - Redis (port 6379)
   - RabbitMQ (ports 5672, 15672)
   - Backend application (port 8080)

4. **Access the application:**
   - Backend API: http://localhost:8080
   - Health Check: http://localhost:8080/actuator/health
   - RabbitMQ Management UI: http://localhost:15672 (guest/guest)
   - Prometheus Metrics: http://localhost:8080/actuator/prometheus

5. **View logs:**
   ```bash
   docker-compose logs -f backend
   ```

6. **Stop services:**
   ```bash
   docker-compose down
   ```

**Note:** Docker Compose uses the multi-stage `Dockerfile` included in this repository. PostgreSQL, Redis, and RabbitMQ data persist in Docker volumes (`postgres_data`, `redis_data`, `rabbitmq_data`) even after stopping containers.

---

## Manual Setup (Without Docker)

If you prefer to run services manually or develop without Docker:

### Configure Environment

Create:

text
src/main/resources/application.properties


Copy values from:

text
application-example.properties


And replace placeholders with your real credentials.

**Important**: Set up your Groq AI API key:
1. Sign up at https://console.groq.com/
2. Get your API key from the dashboard
3. Add to application.properties: `spring.ai.openai.api-key=your_api_key_here`
4. Set the model: `spring.ai.openai.chat.options.model=openai/gpt-oss-120b` or get any available chat model from your groq dashboard


### Run PostgreSQL

Ensure PostgreSQL is running and create your database.


### Run Redis

Ensure Redis is running on:

text
localhost:6379


Install Redis:
- Windows: Download from [Redis official site](https://redis.io/download)
- Mac: `brew install redis`
- Linux: `sudo apt-get install redis-server`

### Run RabbitMQ

Ensure RabbitMQ is running for email processing:
- Windows: Download from [RabbitMQ official site](https://www.rabbitmq.com/download.html)
- Mac: `brew install rabbitmq`
- Linux: `sudo apt-get install rabbitmq-server`

Default RabbitMQ URL: `amqp://guest:guest@localhost:5672`


### Run Application

bash
./mvnw spring-boot:run



# API Security

This project uses cookie-based authentication for enhanced security.

**Authentication Flow:**
- Authentication is cookie-only: `Authorization: Bearer` is intentionally not an accepted credential
- Login sets two `HttpOnly` cookies - `accessToken` (15 min) and `refreshToken` (24 h) - and `AuthResponse` carries only `email` and `role`, so no token ever reaches JavaScript (there is no token field on the DTO to populate)
- `HttpOnly` means application JavaScript cannot read the tokens, so an XSS bug cannot exfiltrate them
- CSRF uses the double-submit pattern: Spring writes a readable `XSRF-TOKEN` cookie (`CsrfCookieFilter`) and the SPA echoes it back in the `X-XSRF-TOKEN` header. `SpaCsrfTokenRequestHandler` publishes the token **XOR-masked** (`XorCsrfTokenRequestAttributeHandler`) and resolves only the masked form back, so there is exactly one representation in play. Masking randomises the published value on every response, defeating BREACH (a compression side-channel): an attacker who injects chosen text into a compressed response and measures size can no longer recover a stable plaintext byte-by-byte. The token is still readable by JavaScript, so masking is not XSS protection - the auth tokens are `HttpOnly` and never reach JavaScript at all.
- `POST`/`PUT`/`PATCH`/`DELETE` are rejected without a matching CSRF token; safe methods (`GET`/`HEAD`/`OPTIONS`) are exempt by default, and the pre-auth endpoints (`/api/auth/**`, `/api/students/auth/**`, `/api/department/auth/**`, `/api/contact/**`) are excluded because no session exists yet on those calls
- `SameSite=Lax` locally, `SameSite=None; Secure` in production (`ENVIRONMENT=production`) so the Vercel SPA can authenticate against the Render API cross-origin
- Refreshing rotates the refresh token and revokes the previous one; each account is capped at 4 live sessions, and a password change revokes every session

**Protected endpoints require:**
- Valid session cookies
- CSRF token for state-changing requests (POST, PUT, DELETE)

**Rate Limiting:**
- Maximum attempts: 4 per hour
- Cooldown period: 60 minutes
- User-friendly error messages with remaining time

## Idempotency

### Components

- `ReplayableRequestWrapper` reads and caches the request body once (1 MB limit). Bodies over 1 MB set an `oversized` flag; the filter rejects them with HTTP 413. Never silently replaces an oversized body with an empty body.
- `IdempotencyWrapperFilter` wraps mutating requests (`POST`/`PUT`/`PATCH`) in `ReplayableRequestWrapper` and the response in `ContentCachingResponseWrapper`. Runs at `Ordered.HIGHEST_PRECEDENCE`, before Spring Security and the MVC interceptor. `copyBodyToResponse()` runs exactly once in a `finally` block. Multipart requests bypass the wrapper (servlet multipart parsing needs the raw stream).
- `IdempotencyInterceptor` Redis state-machine guard (`PROCESSING` / `COMPLETED` / `FAILED`) per scoped key. All state transitions are atomic Lua scripts with per-attempt ownership tokens.

### HTTP-level dedup (`IdempotencyInterceptor`)

All mutating endpoints (`POST`/`PUT`/`PATCH`) on `/api/**` are guarded:

- **Scoped key**: `idempotency:{userId}:{method}:{uri}:{idempotencyKey}` where `userId` is the authenticated user's **database ID** (e.g. `student:42`), not the principal string. One user's key never blocks another user's key, and the same key on different endpoints is independent.
- **Body hash**: SHA-256 of the request body; reusing a key with a different body returns `409 Conflict`. Stale `PROCESSING` reclaim also requires body hash match (return code 3 = 409).
- **States**:
  - `PROCESSING` request is being handled; concurrent duplicates get `409`. Stale entries (older than 5 minutes) are atomically reclaimed via Lua script, but only if the body hash matches.
  - `COMPLETED` request succeeded (2xx); original status, headers, content type and body are replayed on duplicate. Only JSON/text response bodies are stored (64 KB cap); binary responses are marked COMPLETED without a replayable body.
  - `FAILED` request failed (5xx, exception, or 4xx client error); retry is permitted. 4xx responses are client errors, NOT stored as COMPLETED (no replay), and marked FAILED so the key can be reused after the client corrects the request.
- **Ownership token**: a UUID per request attempt ensures only the claimant can transition state or renew the lease, preventing concurrent consumers from overwriting each other.
- **Validation**: keys must match `^[A-Za-z0-9._:-]{1,256}$`; invalid or oversized keys return `400 Bad Request`.
- **Redis failure**: fail-closed if Redis is unavailable, mutating requests get `503 Service Unavailable` to prevent duplicate submissions.
- **TTL**: 24 hours total per key.
- Safe methods (`GET`/`HEAD`/`OPTIONS`/`TRACE`) short-circuit and are never blocked.
- **Transaction completion**: the record becomes `COMPLETED` in `IdempotencyWrapperFilter.finalizeIdempotency` (which runs at `Ordered.HIGHEST_PRECEDENCE` and is the only component with a `ContentCachingResponseWrapper`, so it is the only one able to capture the response body for replay). Callers must ensure the database transaction commits before the response is committed; otherwise a failed DB write surfaces as 5xx and is marked `FAILED` (retryable).

### Email-level dedup (`EmailConsumerService`)

Outbound email (verification, password reset, contact) is consumed from RabbitMQ and deduplicated in Redis. Provides **at-least-once delivery with Redis deduplication** not exactly-once delivery to the external provider, since Redis cannot coordinate atomically with Brevo. If sending succeeds but the Redis state update fails, a redelivery may result in a duplicate email.

- **State machine**: `PROCESSING` / `SENT` / `FAILED` keyed on `email:dedup:<eventId>`.
- **Atomic claim**: `SET IF ABSENT` via Lua script claims `PROCESSING` with a 5-minute TTL. Each processing attempt uses one ownership token for the entire attempt.
- **Active PROCESSING duplicate**: another consumer does NOT silently ack the message is rejected (nacked) so RabbitMQ requeues it for a delayed retry.
- **Lease renewal**: dedicated Lua script (`RENEW_LEASE_SCRIPT`) atomically updates `createdAt` and extends TTL only if the caller owns the token and state is `PROCESSING`.
- **State transitions**: `PROCESSING` ? `SENT` (1-hour TTL) on success, or `PROCESSING` ? `FAILED` (10-minute TTL) on permanent failure. Only the claimant can transition state (atomic owner check in Lua).
- **FAILED state**: terminal within its TTL. After TTL expires, the key is eligible for reclamation by a new claim attempt.
- **Unknown email types**: rejected immediately (marked FAILED, not retried).
- **Retries**: bounded at 3 attempts with exponential backoff + jitter (500 ms base, 0 300 ms jitter). `Retry-After` header respected for Brevo 429. 4xx client errors fail fast.
- **RabbitMQ DLQ**: configured via dead-letter exchange `email.dead.letter` ? queue `email.queue.dlq`. Messages exhausting retries go to the DLQ.
- **Redis failure after sending**: logged as a warning; possible duplicate delivery on redelivery (inherent at-least-once limitation).
- **Serialization failure**: throws an exception never returns fake/empty state.

This works alongside `IdempotencyInterceptor` so complaint submission retries never double-submit and email retries never double-send.
- Processing lease can be renewed during long operations to prevent premature stale reclamation.

---

# Load Testing with K6

This project includes a **read-only, non-mutating** k6 script (`k6/load-test.js`) that exercises login + an authenticated read to measure latency, throughput, and errors under load. It must not change account or application state.

## Prerequisites

- K6 installed: `choco install k6` (Windows) or `brew install k6` (Mac)
- A running backend (local or production) and a valid test account in that environment

## Configuration (required, no defaults)

The script fails fast if any are missing (never hardcode secrets):

| Env var | Description |
| --- | --- |
| `BASE_URL` | Target backend URL (e.g. `https://<app>.onrender.com` or `http://localhost:8080`) |
| `TEST_EMAIL` | Email of a valid account in the target environment |
| `TEST_PASSWORD` | Password for that account |

## Run Load Tests

### Locally

```bash
k6 run -e BASE_URL=http://localhost:8080 -e TEST_EMAIL=<email> -e TEST_PASSWORD=<secret> k6/load-test.js
```

### Against Production

```bash
k6 run -e BASE_URL=https://s2dcms-backend.onrender.com -e TEST_EMAIL=<email> -e TEST_PASSWORD=<secret> k6/load-test.js
```

## Test Configuration

`k6/load-test.js` (single-account, conservative smoke/performance check):

- Flow per iteration: `POST /api/auth/login` (JSON credentials) → extract `accessToken` cookie → `GET /api/auth/me` with `Cookie: accessToken=...` (authenticated, read-only), with `sleep(1)`.
- Stages: ramp to 5 VUs (30s) → hold 5 VUs (2m) → ramp down (30s).
- Thresholds (run fails if breached): `login_duration` p95 < 3000ms, `me_duration` p95 < 2000ms, `errors` count < 20, `http_req_failed` rate < 5%.
- Custom metrics: `login_duration`, `me_duration` (trends) and `errors` (counter).

## CI/CD Integration

K6 runs **on-demand only** in GitHub Actions (`.github/workflows/ci.yml`) — it is **not** triggered by push/PR.

- **Trigger:** manual `workflow_dispatch` (Actions → CI Pipeline → Run workflow).
- **Flow:** `test` runs first (`needs: test`), then `load-test` installs k6 and runs `k6/load-test.js` against the live target.
- **Secrets/env:** `BASE_URL=${{ secrets.RENDER_PROD_URL }}`, `TEST_EMAIL=${{ secrets.K6_TEST_EMAIL }}`, `TEST_PASSWORD=${{ secrets.K6_TEST_PASSWORD }}`.
- **Non-blocking & read-only:** deploy depends on `test` only (not k6), so k6 pass/fail is performance feedback and does not change or gate what is deployed. The script mutates no app/account state.
- **Caveat:** a manual `workflow_dispatch` run from `main` can still trigger `deploy` (it requires only `test`) and redeploy the same commit via the hook; the k6 result does not control this.

---

# Monitoring & Observability

This project uses Spring Boot Actuator, Micrometer, and Prometheus for comprehensive application monitoring.

## Actuator Endpoints

The following endpoints are exposed for monitoring (configured in `application-prod.properties`):

- `/actuator/health` - Application health status
- `/actuator/health/redis` - Redis health check
- `/actuator/metrics` - All available metrics
- `/actuator/prometheus` - Prometheus-formatted metrics
- `/actuator/threaddump` - Thread dump for debugging
- `/actuator/heapdump` - Heap dump for memory analysis
- `/actuator/loggers` - Logger configuration

## Metrics Collected

### Built-in Spring Boot Metrics

- **HTTP Request Metrics**: Request rate, latency (P50, P90, P95, P99), error rate
- **JVM Metrics**: Memory usage, CPU usage, garbage collection, thread count
- **Database Metrics**: HikariCP connection pool (active, idle, max)
- **Redis Metrics**: Connection pool, cache hit/miss rates
- **System Metrics**: CPU, memory, disk usage

### Custom Metrics

- **Method Timing**: `@Timed` annotation on key methods (e.g., login, complaint submission) via `TimedAspect`
- **Circuit Breaker Status**: Resilience4j circuit breaker state for Brevo and Groq
- **Load Test Metrics**: K6 custom metrics for login duration and change password duration

## Production Monitoring (Grafana Cloud)

In production, metrics are automatically pushed to Grafana Cloud Prometheus every 30 seconds (configured in `application-prod.properties`).

### Required Environment Variables (Render)

Set these in your Render service environment variables:

```bash
GRAFANA_CLOUD_PROMETHEUS_URL=https://prometheus-prod-01.grafana.net/api/prom/push
GRAFANA_CLOUD_PROMETHEUS_USER=your_grafana_username
GRAFANA_CLOUD_PROMETHEUS_PASSWORD=your_grafana_api_key
GRAFANA_CLOUD_PROMETHEUS_ENABLED=true
```

**Configuration Details (`application-prod.properties`):**
- Scrape interval: 30s
- All metrics enabled: `management.metrics.enable.all=true`
- Application tag: `s2dcms`
- Environment tag: `production`
- Endpoints exposed: health, metrics, prometheus, threaddump, heapdump, loggers

### Grafana Dashboard Queries

**P95 Latency:**
```promql
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{application="S2dcms"}[5m]))
```

**Error Rate:**
```promql
rate(http_server_requests_seconds_count{application="S2dcms",status=~"5.."}[5m])
```

**JVM Memory Usage:**
```promql
jvm_memory_used_bytes{application="S2dcms"} / jvm_memory_max_bytes{application="S2dcms"}
```

**Database Connection Pool:**
```promql
hikaricp_connections_active{application="S2dcms"} / hikaricp_connections_max{application="S2dcms"}
```

## Local Testing

Test metrics locally with curl:

```bash
# Test health endpoint
curl http://localhost:8080/actuator/health

# Test metrics endpoint
curl http://localhost:8080/actuator/metrics

# Test Prometheus endpoint
curl http://localhost:8080/actuator/prometheus
```

## Circuit Breaker Monitoring

Resilience4j circuit breakers for external services (Brevo, Groq) are monitored via:

- Circuit breaker state (open/closed/half-open)
- Success/failure rates
- Slow call rates

Circuit breaker status is available in the `/actuator/health` endpoint.

# File Uploads

Supported file types:
- PNG
- JPG
- JPEG
- PDF
- DOC
- DOCX

Maximum upload size:
- 5MB

The ceiling is declared in code (`MultipartUploadConfig`) rather than only in
`spring.servlet.multipart.max-file-size`. `application.properties` is git-ignored and never
deployed, so when the limit lived only there production silently ran on Spring Boot's **1MB
default** and rejected 2-3MB files with `MaxUploadSizeExceededException` - even though
`FileStorageService`'s own 5MB check had already passed them. Declaring the
`MultipartConfigElement` bean puts the limit inside the JAR, where it is identical in every
environment. `MultipartUploadConfigTest` fails if the value ever regresses to 1MB.

## Where files are stored

Uploads go to **Supabase Storage** when it is configured, and to the local filesystem
otherwise.

| Concern | Supabase Storage | Local filesystem (`file.dir`) |
| --- | --- | --- |
| Survives a Render deploy | Yes | **No** |
| Survives a free-tier cold start | Yes | **No** |
| Use in production | **Required** | Never |

Render's free tier hands out a **brand new container** on every deploy and on every
free-tier cold start. Anything written inside that container is destroyed with it, while the
database rows pointing at those files survive - so profile pictures silently disappear on
each redeploy. This is why uploads must live outside the container.

### Setup (production)

1. In the Supabase dashboard: **Storage -> New bucket**, named e.g. `s2dcms-uploads`.
   **Leave it private** - files are streamed out through this backend, so nothing needs to be
   publicly readable. Your existing size limit (5MB) and MIME-type allow-list are enforced by
   the bucket as a second line of defence, behind the checks in `FileStorageService`.
2. In **Project Settings -> API**, copy the **Project URL** and the **service_role** key.
3. Set these on Render:

bash
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key
SUPABASE_STORAGE_BUCKET=s2dcms-uploads


`SupabaseStorageService` speaks the Storage REST API directly with Spring's `RestClient`
rather than pulling in the Supabase SDK - it is one POST, one GET and one DELETE, so a
dependency and its transitive version conflicts would not pay for themselves.

### How a file is served

The bucket is private, so the browser never talks to Supabase directly.
`UploadedFileController` reads the bytes with the service-role key and streams them back at
the **same `/uploads/...` URL the app has always used**:


browser  ---GET /uploads/profile/<uuid>_pic.png--->  backend  ---(service key)--->  private bucket
        <---------------------------------- bytes ---------------------------------->


That has two useful consequences: **no database migration and no frontend change** were
needed (only the bytes moved), and the service-role key is never exposed to the browser.

> The `service_role` key bypasses row-level security, so it is read from configuration and
> never leaves the server. Keep it in a Render environment variable only; never commit it
> and never expose it to the SPA.

`WebConfig` registers its static `/uploads/**` resource handler **only** when Supabase is not
configured - otherwise Spring would answer from the empty container directory and the stored
file would never be read.

Provider errors are logged server-side and replaced with one fixed client-safe message, so
the bucket name, project id and key are never returned to the browser. Failed *deletes* are
logged and swallowed, matching the previous behaviour - removing a profile picture must still
succeed even if the bucket is briefly unreachable.

Files stored **before** this change still hold `/uploads/profile/<uuid>_pic.png` paths and will
be found once re-uploaded, but their bytes were destroyed with the container they lived in -
**re-upload those images once** to repopulate the bucket.


# Architecture Highlights

```mermaid
graph LR
    subgraph "Entry"
        Client[Browser / SPA]
    end

    subgraph "Filter Chain"
        F1[IdempotencyWrapperFilter<br/>Ordered.HIGHEST_PRECEDENCE]
        F2[Spring Security<br/>JWT + CSRF + RBAC]
    end

    subgraph "Controller Layer"
        C1[StudentController]
        C2[DepartmentController]
        C3[AdminController]
        C4[AuthController]
        C5[AIController]
        C6[HealthController]
    end

    subgraph "Service Layer"
        S1[AuthService<br/>JWT + Refresh Rotation]
        S2[StudentService]
        S3[DepartmentService]
        S4[EmailService<br/>RabbitMQ -> Brevo]
        S5[AIComplaintService<br/>Groq REST]
        S6[FileStorageService<br/>Supabase / Local]
    end

    subgraph "Aspect / Cross-cutting"
        A1[TimedAspect<br/>Micrometer Timer]
        A2[IdempotencyInterceptor<br/>Redis Lua State Machine]
        A3[RateLimiter<br/>PostgreSQL attempts]
    end

    subgraph "Data Access"
        R1[StudentRepository]
        R2[DepartmentRepository]
        R3[ComplaintRepository]
        R4[MessageRepository]
        R5[RefreshTokenRepository]
        R6[AttemptRepository]
    end

    subgraph "External"
        PG[(PostgreSQL<br/>Supabase / Local)]
        RD[(Redis<br/>Cache + Sessions)]
        MQ[RabbitMQ<br/>CloudAMQP / Local]
        BR[Brevo<br/>Email API]
        GQ[Groq<br/>AI API]
        SB[Supabase<br/>Storage]
    end

    Client -->|HTTPS + Cookies| F1
    F1 --> F2
    F2 --> C1
    C1 --> S2
    C2 --> S3
    C3 --> S3
    C4 --> S1
    C5 --> S5
    C6 --> S2
    S1 --> R5
    S2 --> R1
    S3 --> R2
    S4 --> MQ
    S5 --> GQ
    S6 --> SB
    A1 -.->|@Timed| C1
    A2 -.->|Idempotency-Key| F1
    A3 -.->|attempt check| S1
    R1 --> PG
    R2 --> PG
    R3 --> PG
    R4 --> PG
    R5 --> PG
    R6 --> PG
    S2 --> RD
    S3 --> RD
    MQ --> BR
```

The flow above shows the request path from browser through the filter chain, into controllers, down through services and aspects, and finally to repositories and external dependencies.

- **Layered Architecture**: Controller -> Service -> Repository pattern for clean separation of concerns
- **DTO-based API responses**: Clean separation between internal models and API contracts
- **Service-oriented design**: Business logic encapsulated in service layer
- **Cookie-Based Authentication**: HttpOnly cookies with CSRF protection (no localStorage tokens)
- **Advanced token management**: Refresh token rotation with session limits (max 4 active sessions) and a scheduled cleanup sweep every 5 hours
- **Database-based token storage**: PostgreSQL persistence for refresh tokens (survives server restarts)
- **Database-based rate limiting**: PostgreSQL-tracked login attempts with a configurable attempt cap and cooldown window, remaining-time messaging, and automatic reset on successful authentication
- **Redis caching + health monitoring**: annotation-driven caching with a 10-minute TTL, graceful fall-through to PostgreSQL when Redis is unavailable, and an automated health PING every 5 minutes
- **Secure file handling**: Validation, size limits (5MB), and Supabase Storage uploads so files survive a container rebuild (with a local-disk fallback for development)
- **Role-based endpoint protection**: Spring Security with custom JWT authentication filters
- **Async email processing**: RabbitMQ message queue for email operations
- **Database migrations**: Flyway for version-controlled schema changes
- **Database optimization**: Strategic indexing on frequently accessed columns for query performance
- **AI Integration**: Direct RestClient calls to Groq API for complaint summarization and reply suggestions
- **Comprehensive security headers**: HSTS, CSP, frameOptions for defense in depth
- **Environment-aware configuration**: Automatic detection of development vs production environments

## System Architecture
# Deployment

## Production Hosting Stack
- **Backend Hosting**: Render (free web service) - https://s2dcms-backend.onrender.com
- **Database**: Supabase PostgreSQL (permanent free tier)
- **Cache**: Redis Cloud (free tier)
- **Message Queue**: CloudAMQP RabbitMQ (free tier)
- **Uptime Monitoring**: UptimeRobot (free tier)
- **Frontend**: Vercel (free tier) - https://student-complaints-tau.vercel.app

## Environment Variables for Production

The following environment variables should be set on Render:

bash
# PostgreSQL (Supabase)
SPRING_DATASOURCE_URL=jdbc:postgresql://your-supabase-host:5432/postgres
SPRING_DATASOURCE_PASSWORD=your-supabase-password

# Supabase Storage (uploads - required, see "File Uploads")
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key
SUPABASE_STORAGE_BUCKET=s2dcms-uploads

# Redis (Redis Cloud)
SPRING_DATA_REDIS_HOST=your-redis-host
SPRING_DATA_REDIS_PORT=your-redis-port
SPRING_DATA_REDIS_PASSWORD=your-redis-password

# RabbitMQ (CloudAMQP)
SPRING_RABBITMQ_HOST=your-rabbitmq-host
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=your-username
SPRING_RABBITMQ_PASSWORD=your-password

# Groq AI (Spring relaxed binding maps this onto spring.ai.openai.api-key)
SPRING_AI_OPENAI_API_KEY=your-groq-api-key

# Secrets and overrides resolved the same way (no code change needed)
JWT_SECRET=a-long-random-secret-of-at-least-32-characters
BREVO_API_KEY=your-brevo-api-key
BREVO_SENDER_EMAIL=support@your-domain.com
ADMIN_EMAIL=admin@your-domain.com

# Environment switch: enables SameSite=None; Secure cookies for Vercel -> Render
ENVIRONMENT=production


> Every value above is read through Spring Boot's relaxed binding, so an upper-case
> environment variable with underscores maps onto the dotted property name
> (for example `SPRING_DATASOURCE_USERNAME` -> `spring.datasource.username`).
> `application.properties` is git-ignored; only `application-example.properties`
> is published, and it contains placeholders instead of credentials.

### `application-prod.properties` - where the tuning lives

Non-secret tuning (Hikari pool, Groq AI timeouts, email concurrency, cache TTL,
logging) is **not** typed as Render env vars. It lives in a committed
`application-prod.properties` file so it is version-controlled and reviewable
instead of trapped in a web UI and silently lost on every rebuild.

To activate it on Render, add **one** env var:

bash
SPRING_PROFILES_ACTIVE=prod


How it interacts with the env vars above:

- **Every secret** (`SPRING_DATASOURCE_URL`, `JWT_SECRET`, `BREVO_API_KEY`,
  `SUPABASE_*`, `SPRING_DATA_REDIS_*`, `SPRING_RABBITMQ_*`, `SPRING_AI_OPENAI_API_KEY`)
  stays a `${ENV_VAR}` placeholder in the file - the real value lives only on Render.
- **Render env vars outrank the file.** If you set the same key both places, the
  Render value wins, so a secret can never be overridden from git.
- **Secrets never leave Render.** The committed file contains no credentials, so
  pushing it is safe.
- **`ENVIRONMENT=production` is the one exception** - the auth cookie logic reads
  it with `System.getenv()`, which only sees process env vars and ignores properties
  files, so it must stay a real Render env var (see "Production Deployment" below).
- Placeholders carry safe `:default` fallbacks (and empty defaults for optional
  services) so a missing non-critical var degrades gracefully instead of crashing
  boot. The single exception is `JWT_SECRET`, which intentionally has **no** default:
  auth must fail fast rather than silently run on an empty key.

## Health Check & Monitoring

### Health Endpoints
- **Backend Health**: `/health` - Returns backend status (used by UptimeRobot)
- **Redis Health**: `/health/redis` - Returns Redis connection status

### Timestamps and Timezone

Timestamps are written with `LocalDateTime.now()` and are **UTC**. Two things make that explicit so
a viewer's browser renders the correct local time:

- The `Dockerfile` pins the container clock (`ENV TZ=UTC` and `-Duser.timezone=UTC`), so the stored
  value is UTC in every environment regardless of host configuration.
- `JacksonUtcConfig` serialises `LocalDateTime` with an explicit `Z` designator. Without it Jackson
  emits `2026-10-01T18:40:44` with no zone marker, and ECMAScript parses that exact shape as
  **local** time - so a GMT+1 browser displayed a UTC instant an hour early, with nothing in the
  payload to correct it. The frontend then formats with `toLocaleString()`, which converts into the
  viewer's own timezone.

Existing rows need no migration: they were already written from a UTC clock, so only the wire format
was wrong.

### Monitoring Strategy
- **UptimeRobot**: Pings `/health` every 5 minutes to prevent Render backend sleep
- **RedisHealthScheduler**: a Spring `@Scheduled(fixedRate = 300000)` task PINGs Redis every 5 minutes so the free-tier database is not marked inactive during idle periods; the result is surfaced through `/health/redis`
- **Heartbeat Storage**: Redis key `heartbeat:last_ping` stores last activity timestamp
- **Hourly Status Reports**: Scheduled logging of Redis connection status

### Production Deployment
Set `ENVIRONMENT=production` environment variable on Render to enable:
- Secure cookie flag (HTTPS required)
- SameSite=None (cross-origin support for Vercel + Render)
- All production security configurations

## Deployment Steps

1. **Set up cloud services** (Supabase, Redis Cloud, CloudAMQP)
2. **Push code to GitHub**
3. **Connect repository to Render**
4. **Configure environment variables** (secrets, plus `SPRING_PROFILES_ACTIVE=prod` to load the committed tuning file)
5. **Deploy and test**
6. **Set up UptimeRobot to ping /health endpoint**

## Local vs Production Configuration

- **Local**: Runs on the (git-ignored) `application.properties` with your real local
  credentials, pointing at local PostgreSQL, Redis and RabbitMQ.
- **Production**: Runs with `SPRING_PROFILES_ACTIVE=prod`, which loads the committed
  `application-prod.properties` (tuning only, no secrets). Connection strings and
  secrets are supplied as Render environment variables, which outrank the file.
- **`application-example.properties`** is never loaded at runtime - it is only a
  reference template for the local file. Renaming or activating it as a profile
  would leak dev values (`ddl-auto=update`, `show-sql=true`, `localhost` URLs) into
  production, so it is intentionally inert.
- Configuration uses Spring Boot's environment variable fallback pattern: `${VAR_NAME:default_value}`.

## Database connection pooling

`application-prod.properties` refers to this section from its Hikari block.

Production connects to Supabase's **shared pooler (Supavisor)**, and the port
you use decides whether the app can boot at all. Pool size is a side issue.

### Why session mode (port 5432) crashes the app

A Postgres connection is a *session*: it stays open until someone closes it,
whether it is currently running queries or sitting idle. Supabase states the
consequence directly:

> In Session mode, hypothetically, the clients that first accessed the direct
> connections could keep them, **even if they are no longer executing queries.**

So in session mode every client is handed a dedicated backend and keeps it for
as long as it stays connected. The pooler allows only `pool_size` of them -
**15 for this project** - and Supabase's own services (Auth, Storage,
PostgREST) draw from that same budget.

That is what produces this crash loop on Render:

1. A deploy crashes, or Render replaces it with a new one.
2. The dead container's JDBC connections are never cleanly closed, so the
   pooler still counts them as in use. They become orphaned idle sessions.
3. Each redeploy leaks a few more until all 15 slots are taken.
4. The next deploy boots and Flyway immediately asks for **one** connection to
   check its migrations. There isn't one left.


FATAL: (EMAXCONNSESSION) max clients reached in session mode - max clients are limited to pool_size: 15


The failure happens at Flyway's **very first** connection - before the app's
own Hikari pool has been sized or filled - which is why shrinking the pool does
not help. Commits `9fd20d1` and `9904616` capped Hikari and still crashed: the
budget that ran out belongs to the *pooler*, not to the application.

### Why transaction mode (port 6543) fixes it

Transaction mode does not give a client a private session. It lends you a
backend only while a query or transaction is actually running, and takes it
back the moment that finishes. Nothing is held while idle, so:

- there are no long-lived sessions to orphan when a deploy crashes;
- one shared backend serves every query from every client;
- the crash loop can no longer refill a budget it is never given.

The two modes are accounted for separately, so a jammed 5432 pool does not
limit 6543 - switching over can succeed while the old sessions are still stuck.

### The connection string

Set `SPRING_DATASOURCE_URL` on Render to this exact form (note the `6543`):


jdbc:postgresql://aws-1-eu-west-1.pooler.supabase.com:6543/postgres?prepareThreshold=0


Take the host from **Supabase Dashboard -> Connect -> Transaction pooler** rather
than typing it: the `aws-N-` cluster index is per project and is not derivable
from the region. Leave `SPRING_DATASOURCE_USERNAME` (`postgres.<project-ref>`),
`SPRING_DATASOURCE_PASSWORD`, and every other env var **unchanged** - only the
URL differs. Save, and Render redeploys.

**`prepareThreshold=0` is not optional.** Supabase documents this exact JDBC
form:

> JDBC - Set `prepareThreshold` to `0`:
> `jdbc:postgresql://[POOLER-HOST]:6543/postgres?prepareThreshold=0`

Transaction mode has no server-side prepared statements. Skip the flag and the
app boots but dies on its first query with
`prepared statement ... does not exist`.

### Why this is safe for this codebase

Dropping session state between transactions rules out `SET`/`RESET`, session
advisory locks, `LISTEN`/`NOTIFY`, and temporary tables. This application uses
none of them (verified across the source tree). The Supavisor pooler is IPv4,
so it is reachable from Render's IPv4-only egress, and Flyway 11 takes
transactional migration locks, so migrations run cleanly through transaction
pooling.

### Clearing stale sessions (optional hygiene)

Old failed deploys may still be holding idle sessions on 5432. Reclaim them in
the Supabase SQL editor:

sql
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE application_name = 'PostgreSQL JDBC Driver' AND state = 'idle';


This is cleanup only - it does not stop the crash. Render's build takes minutes
before Flyway runs, so the crash loop wins the race and refills the slots. Only
the switch to 6543 removes the failure mode.

# Author

Developed by Eze Emmanuel

---

## Observability & Monitoring

### Actuator Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Overall health (DB, Redis, RabbitMQ, circuit breakers) |
| `GET /actuator/metrics` | All Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape format |
| `GET /actuator/threaddump` | Thread snapshot for debugging blocked threads |
| `GET /actuator/loggers` | Log level configuration |
| `GET /actuator/env` | Environment properties |

### Custom Metrics

The `@Timed` aspect records per-method latency as a Micrometer timer:

java
@Timed(value = "auth.login", warnThresholdMs = 800)
@PostMapping("/auth/login")
public ResponseEntity<AuthResponse> login(...) { ... }


Metrics are tagged with `method`, `class`, and `application`, so Prometheus/Grafana can break down p95 latency per endpoint.

### Grafana Cloud (Free Tier)

Set these Render environment variables to stream metrics to Grafana Cloud:

| Variable | Description |
|----------|-------------|
| `GRAFANA_CLOUD_PROMETHEUS_URL` | Prometheus remote-write URL |
| `GRAFANA_CLOUD_PROMETHEUS_USER` | Grafana Cloud username |
| `GRAFANA_CLOUD_PROMETHEUS_PASSWORD` | Grafana Cloud API key |
| `GRAFANA_CLOUD_PROMETHEUS_ENABLED` | Set to `true` to enable remote write |

Then import the **Spring Boot 2.x** dashboard (Grafana ID 10392) for pre-built JVM + HTTP dashboards.

### Circuit Breakers (Resilience4j)

| Service | Failure threshold | Wait in open | Half-open calls | Purpose |
|---------|------------------|--------------|-----------------|---------|
| `brevo` | 50% | 30s | 3 | Email API |
| `groq` | 50% | 30s | 3 | AI summarization |

Check breaker state:
bash
curl https://s2dcms-backend.onrender.com/actuator/metrics/resilience4j.circuitbreaker.state


Brevo calls use `@CircuitBreaker("brevo")` + `@Retry("brevo")` (3 attempts, 2s fixed wait).
Groq calls use `@CircuitBreaker("groq")` only the existing manual retry (3 attempts with exponential backoff + jitter) is preserved.

---

## CI/CD Pipeline (GitHub Actions)

### Workflow: `.github/workflows/ci.yml`

| Job | Runs on | Triggers / needs | Purpose |
|-----|---------|------------------|---------|
| `test` | ubuntu-latest (CI provisions PostgreSQL/Redis/RabbitMQ services) | push/PR to `main`, `workflow_dispatch` | `mvn test -B` (tests use self-contained H2/stubbed config), build JAR, upload artifact (correctness gate) |
| `load-test` | ubuntu-latest | `workflow_dispatch` only, `needs: test` | Read-only k6 against live target (non-blocking feedback) |
| `deploy` | ubuntu-latest | `main` push (not PR), `needs: [test]` only | POST `RENDER_DEPLOY_HOOK` to deploy |

**Key behavior:** functional tests gate deploy; k6 is manual/on-demand and does **not** gate deploy. A manual `workflow_dispatch` from `main` can still run `deploy` (it depends only on `test`).

### Secrets required on GitHub

| Secret | Description |
|--------|-------------|
| `RENDER_DEPLOY_HOOK` | Render webhook URL to trigger redeploy |
| `RENDER_PROD_URL` | Production backend URL used by k6 as `BASE_URL` |
| `K6_TEST_EMAIL` | k6 test-account email |
| `K6_TEST_PASSWORD` | k6 test-account password |

### Load Test (k6)

`k6/load-test.js` is **read-only** (login + authenticated `GET /api/auth/me`), fails fast without `BASE_URL/TEST_EMAIL/TEST_PASSWORD`, and enforces thresholds: `login_duration` p95 < 3000ms, `me_duration` p95 < 2000ms, `errors` < 20, `http_req_failed` < 5%. It mutates no state and a pass/fail does not deploy or roll back code. See [Load Testing with K6](#load-testing-with-k6) for local/manual run details.

---

## Local Development Stack (Docker Compose)

```bash
# Start PostgreSQL, Redis, RabbitMQ + backend
docker-compose up -d

# Backend health
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus | head -30

# Run read-only load test locally (requires a valid local test account)
k6 run -e BASE_URL=http://localhost:8080 -e TEST_EMAIL=<email> -e TEST_PASSWORD=<secret> k6/load-test.js
```

Required env vars for `.env` (copy from `.env.example`):

BREVO_API_KEY=your_brevo_key
SPRING_PROFILES_ACTIVE=dev


---

## Production Deployment

### Render (Backend)

1. Push to `main` → GitHub Actions runs `test` (unit/integration) and triggers `deploy` on success (deploy gated on `test` only).
2. Render pulls the latest commit/image, runs Flyway migrations, starts app.
3. Health check: `/actuator/health` must return UP.
4. (Optional) Run **CI Pipeline** manually (`workflow_dispatch`) to execute the read-only k6 load test against the live URL for threshold-based performance feedback; it does not gate or modify deployment.

### Vercel (Frontend)

1. Push to `main` ? Vercel auto-deploys
2. Frontend calls backend via `VITE_API_BASE_URL`

### Required Render Environment Variables


SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://...:6543/...?prepareThreshold=0
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
SPRING_RABBITMQ_HOST=...
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=...
SPRING_RABBITMQ_PASSWORD=...
GRAFANA_CLOUD_PROMETHEUS_URL=...
GRAFANA_CLOUD_PROMETHEUS_USER=...
GRAFANA_CLOUD_PROMETHEUS_PASSWORD=...
GRAFANA_CLOUD_PROMETHEUS_ENABLED=true
BREVO_API_KEY=...
SPRING_AI_OPENAI_API_KEY=...
ENVIRONMENT=production



