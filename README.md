# S2DCMS Backend

Spring Boot backend API for the Student to Department Complaint Management System.

## Related Repositories

- **Frontend**: [s2dcms-frontend](https://github.com/emmanuel-40/s2dcms-frontend) - React frontend with Vite and Tailwind CSS
- **API reference**: [API_DOCUMENTATION.md](API_DOCUMENTATION.md) - request/response contract for every endpoint

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file in this repository for details.

## Overview

This backend provides REST APIs for student and department authentication, complaint management, file uploads, and messaging with JWT-based security, Redis caching, and RabbitMQ email processing.

## Project at a Glance

| Area |                                                         Detail |
 
| **Live API** |         https://s2dcms-backend.onrender.com (`/health`, `/health/redis`) |
| **Frontend** |         https://student-complaints-tau.vercel.app |
| **API surface** |      34 documented endpoints across 6 controllers (student, department/admin, auth, AI,    contact, health) |
| **Codebase** |         83 Java classes — 13 services, 6 controllers, 6 repositories, 13 security/config classes |
| **Data layer** |      PostgreSQL, 16 Flyway migrations, Redis cache, RabbitMQ e-mail queue |
| **Auth model** |      HttpOnly-cookie JWT, refresh-token rotation, 4-session cap, 3 role-based access policies |
| **Quality** |         38 automated tests (integration through the real security filter chain + unit) |
| **API reference** | [API_DOCUMENTATION.md](API_DOCUMENTATION.md) — per-endpoint request/response contracts |
| **Container** |     Multi-stage `Dockerfile` (Maven build → Temurin JRE runtime) |


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
- **Docker** - Container-ready via a multi-stage `Dockerfile` (`maven:3.9-eclipse-temurin-17` build stage → `eclipse-temurin:17-jre` runtime stage)


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

Authentication, session handling, and AI failure handling are covered by an automated suite. Integration tests run against a real Spring context, including the real security filter chain — the security behaviour is tested as it is deployed, not as a mock.

```bash
cd S2dcms-backend
mvn test        # surefire prints the per-class and total counts
```

> Per-class counts are deliberately not quoted here: the last run is the only source of truth, and a
> number copied into a README goes stale the first time anyone adds a test.

### 1. `AuthControllerIntegrationTest` — integration (MockMvc through the real filter chain)

`@SpringBootTest` + `MockMvc` drives the full `SecurityFilterChain` — JWT cookie filter → CSRF → authorization — against an H2 database. What it verifies, in request order:

1. **Login** — both `STUDENT` and `DEPARTMENT` accounts authenticate and receive cookies
2. **Cookie attributes** — `HttpOnly`, `SameSite`, `Path` and `Max-Age` are asserted on every `Set-Cookie`
3. **Response body** — contains only `email` and `role`; no token is ever serialised to the client
4. **Header refusal** — an `Authorization: Bearer …` request is rejected; cookies are the only accepted credential
5. **CSRF** — required on unsafe methods, skipped for safe methods; a missing token on an authenticated request is `403` with an actionable message (never `401`), and `GET /api/auth/csrf` serves the token to cross-origin SPAs as JSON
6. **Refresh** — the token rotates and the previous token is revoked
7. **Tampering** — modified, unknown and expired cookies are all rejected
8. **Logout** — cookies are cleared and the session is revoked server-side
9. **Password change** — every existing refresh token is invalidated
10. **Rate limiting** — repeated failures lock further attempts for the cooldown window

### 2. `AiProviderExceptionTest` — unit

The AI endpoints promise a status a client can act on without ever repeating what the provider said, so both halves of that promise are asserted directly: provider `429` becomes `429` carrying the upstream `Retry-After` (falling back to 30s when it is missing, unparsable, or absurd), outages and connectivity failures become `503`, a rejected payload becomes `502`, an already-classified error survives being rethrown, and a broken API key on our side surfaces as `503` with no key/provider/quota wording in the body.

### 3. `AuthServiceTest` — unit

Mockito with mocked repositories: verifies authentication decisions and their side effects in isolation from the web and persistence layers.

### 4. `MultipartUploadConfigTest` — integration

Asserts the servlet upload ceiling is the configured 5MB and not Spring Boot's 1MB default, and that the request ceiling is never below the file ceiling. This is a regression test for a production-only failure: the limit lived in a git-ignored properties file, so it was correct locally and 1MB in production.

### 5. `S2dcmsApplicationTests` — smoke

Spring context load: confirms the application boots with the current configuration.

---

Test names describe observable behaviour rather than implementation detail, so the suite doubles as the executable specification for the auth design.

# Setup Instructions

## Clone Repository

```bash
git clone https://github.com/emmanuel-40/s2dcms-backend.git
cd s2dcms-backend
```

---

## Configure Environment

Create:

```text
src/main/resources/application.properties
```

Copy values from:

```text
application-example.properties
```

And replace placeholders with your real credentials.

**Important**: Set up your Groq AI API key:
1. Sign up at https://console.groq.com/
2. Get your API key from the dashboard
3. Add to application.properties: `spring.ai.openai.api-key=your_api_key_here`
4. Set the model: `spring.ai.openai.chat.options.model=openai/gpt-oss-120b` or get any available chat model from your groq dashboard


## Run PostgreSQL

Ensure PostgreSQL is running and create your database.


## Run Redis

Ensure Redis is running on:

```text
localhost:6379
```

Install Redis:
- Windows: Download from [Redis official site](https://redis.io/download)
- Mac: `brew install redis`
- Linux: `sudo apt-get install redis-server`

## Run RabbitMQ

Ensure RabbitMQ is running for email processing:
- Windows: Download from [RabbitMQ official site](https://www.rabbitmq.com/download.html)
- Mac: `brew install rabbitmq`
- Linux: `sudo apt-get install rabbitmq-server`

Default RabbitMQ URL: `amqp://guest:guest@localhost:5672`


## Run Application

```bash
./mvnw spring-boot:run
```


# API Security

This project uses cookie-based authentication for enhanced security.

**Authentication Flow:**
- Authentication is cookie-only: `Authorization: Bearer` is intentionally not an accepted credential
- Login sets two `HttpOnly` cookies — `accessToken` (15 min) and `refreshToken` (24 h) — and `AuthResponse` carries only `email` and `role`, so no token ever reaches JavaScript (there is no token field on the DTO to populate)
- `HttpOnly` means application JavaScript cannot read the tokens, so an XSS bug cannot exfiltrate them
- CSRF uses the double-submit pattern: Spring writes a readable `XSRF-TOKEN` cookie (`CsrfCookieFilter`) and the SPA echoes it back in the `X-XSRF-TOKEN` header (`SpaCsrfTokenRequestHandler` normalises the token for SPA use)
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
default** and rejected 2-3MB files with `MaxUploadSizeExceededException` — even though
`FileStorageService`'s own 5MB check had already passed them. Declaring the
`MultipartConfigElement` bean puts the limit inside the JAR, where it is identical in every
environment. `MultipartUploadConfigTest` fails if the value ever regresses to 1MB.

## Where files are stored

Uploads go to **Supabase Storage** when it is configured, and to the local filesystem
otherwise.

                                             | Supabase Storage | Local filesystem (`file.dir`) |
|
| Survives a Render deploy                     | Yes |                   | **No** |
| Survives a free-tier cold start              | Yes |                   | **No** |
| Use in production                            | **Required** |          | Never |

Render's free tier hands out a **brand new container** on every deploy and on every
free-tier cold start. Anything written inside that container is destroyed with it, while the
database rows pointing at those files survive - so profile pictures silently disappear on
each redeploy. This is why uploads must live outside the container.

### Setup (production)

1. In the Supabase dashboard: **Storage → New bucket**, named e.g. `s2dcms-uploads`.
   **Leave it private** — files are streamed out through this backend, so nothing needs to be
   publicly readable. Your existing size limit (5MB) and MIME-type allow-list are enforced by
   the bucket as a second line of defence, behind the checks in `FileStorageService`.
2. In **Project Settings → API**, copy the **Project URL** and the **service_role** key.
3. Set these on Render:

```bash
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key
SUPABASE_STORAGE_BUCKET=s2dcms-uploads
```

`SupabaseStorageService` speaks the Storage REST API directly with Spring's `RestClient`
rather than pulling in the Supabase SDK — it is one POST, one GET and one DELETE, so a
dependency and its transitive version conflicts would not pay for themselves.

### How a file is served

The bucket is private, so the browser never talks to Supabase directly.
`UploadedFileController` reads the bytes with the service-role key and streams them back at
the **same `/uploads/...` URL the app has always used**:

```
browser  ──GET /uploads/profile/<uuid>_pic.png──▶  backend  ──(service key)──▶  private bucket
        ◀────────────────────── bytes ────────────┘
```

That has two useful consequences: **no database migration and no frontend change** were
needed (only the bytes moved), and the service-role key is never exposed to the browser.

> The `service_role` key bypasses row-level security, so it is read from configuration and
> never leaves the server. Keep it in a Render environment variable only; never commit it
> and never expose it to the SPA.

`WebConfig` registers its static `/uploads/**` resource handler **only** when Supabase is not
configured — otherwise Spring would answer from the empty container directory and the stored
file would never be read.

Provider errors are logged server-side and replaced with one fixed client-safe message, so
the bucket name, project id and key are never returned to the browser. Failed *deletes* are
logged and swallowed, matching the previous behaviour — removing a profile picture must still
succeed even if the bucket is briefly unreachable.

Files stored **before** this change still hold `/uploads/profile/<uuid>_pic.png` paths and will
be found once re-uploaded, but their bytes were destroyed with the container they lived in —
**re-upload those images once** to repopulate the bucket.


# Architecture Highlights

- **Layered Architecture**: Controller → Service → Repository pattern for clean separation of concerns
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

```
┌─────────────────────────────────────────────────────────────────┐
│                         Frontend (React)                        │
│                    http://localhost:5173                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │  Student     │  │  Department  │  │    Admin     │           │
│  │   Portal     │  │   Portal     │  │   Portal     │           │
│  └──────────────┘  └──────────────┘  └──────────────┘           │ 
│  ┌──────────────┐                                               │
│  │   Public     │                                               │
│  │   Pages      │                                               │
│  └──────────────┘                                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ HTTP/HTTPS
                              │ Cookie Auth + CSRF
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Backend (Spring Boot)                        │
│                    http://localhost:8080                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Security Layer (Spring Security)            │   │
│  │  - Cookie-Based Authentication (HttpOnly)                │   │
│  │  - CSRF Protection (X-XSRF-TOKEN)                        │   │
│  │  - Role-Based Access Control (STUDENT/DEPARTMENT/ADMIN)  │   │
│  │  - Rate Limiting                                         │   │
│  │  - Security Headers (HSTS, CSP, frameOptions)            │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Controller Layer                            │   │
│  │  - AuthController                                        │   │
│  │  - StudentController (with admin endpoints)              │   │
│  │  - DepartmentController (with admin endpoints)           │   │
│  │  - AIController                                          │   │
│  │  - HealthController                                      │   │
│  │  - ContactController                                     │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Service Layer                               │   │
│  │  - AuthService                                           │   │
│  │  - StudentService                                        │   │
│  │  - DepartmentService                                     │   │
│  │  - AIComplaintService (Groq API)                         │   │
│  │  - FileStorageService                                    │   │
│  │  - RedisHealthScheduler (automated Redis monitoring)     │   │
│  │  - RefreshTokenService (rotation + revocation)           │   │
│  │  - UserActionService (login rate limiting)               │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Repository Layer (JPA)                      │   │
│  │  - StudentRepo / DepartmentRepo / MessageRepo            │   │
│  │  - RefreshTokenRepository (session state)                │   │
│  │  - UserActionLimitRepository (rate limiting)             │   │
│  │  - ContactMessageRepository                              │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│   PostgreSQL     │  │     Redis        │  │    RabbitMQ      │
│   (Database)     │  │   (Cache +       │  │  (Email Queue)   │
│  localhost:5432  │  │    Health)       │  │  localhost:5672  │
└──────────────────┘  │  localhost:6379  │  └──────────────────┘
                      └──────────────────┘
                              ▲
                              │ Internal PING (5 min)
                              │
┌─────────────────────────────────────────────────────────────────┐
│                    External Monitoring                          │
│  - UptimeRobot → /health (keeps Render backend awake)           │
│  - RedisHealthScheduler → Internal Redis monitoring             │
└─────────────────────────────────────────────────────────────────┘

                       │
                       │  outbound call from AIComplaintService
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                          Groq AI Cloud                          │
│  OpenAI-compatible POST /chat/completions                       │
│  Spring RestClient · model openai/gpt-oss-120b                  │
│  Bearer key supplied via SPRING_AI_OPENAI_API_KEY               │
└─────────────────────────────────────────────────────────────────┘
```


# Deployment

This application is deployed using free-tier hosting services:

## Production Hosting Stack
- **Backend Hosting**: Render (free web service) - https://s2dcms-backend.onrender.com
- **Database**: Supabase PostgreSQL (permanent free tier)
- **Cache**: Redis Cloud (free tier)
- **Message Queue**: CloudAMQP RabbitMQ (free tier)
- **Uptime Monitoring**: UptimeRobot (free tier)
- **Frontend**: Vercel (free tier) - https://student-complaints-tau.vercel.app

## Environment Variables for Production

The following environment variables should be set on Render:

```bash
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

# Environment switch: enables SameSite=None; Secure cookies for Vercel → Render
ENVIRONMENT=production
```

> Every value above is read through Spring Boot's relaxed binding, so an upper-case
> environment variable with underscores maps onto the dotted property name
> (for example `SPRING_DATASOURCE_USERNAME` → `spring.datasource.username`).
> `application.properties` is git-ignored; only `application-example.properties`
> is published, and it contains placeholders instead of credentials.

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
  **local** time — so a GMT+1 browser displayed a UTC instant an hour early, with nothing in the
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
4. **Configure environment variables**
5. **Deploy and test**
6. **Set up UptimeRobot to ping /health endpoint**

## Local vs Production Configuration

- **Local**: Uses local PostgreSQL, Redis, RabbitMQ (default values in application.properties)
- **Production**: Uses cloud services via environment variables
- Configuration uses Spring Boot's environment variable fallback pattern: `${VAR_NAME:default_value}`

# Author

Developed by Eze Emmanuel
