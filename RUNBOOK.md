# S2DCMS Backend — Runbook

On-call guide for the S2DCMS backend. Intended for engineers responding to incidents, performing routine checks, or deploying changes.

---

## Service Contacts

| Role | Contact | Slack |
|------|---------|-------|
| Primary On-Call | @backend-oncall | #s2dcms-alerts |
| Secondary On-Call | @backend-secondary | #s2dcms-alerts |

---

## Service Endpoints

| Service | URL | Purpose |
|---------|-----|---------|
| Backend (Prod) | https://s2dcms-backend.onrender.com | Main API |
| Backend Health | https://s2dcms-backend.onrender.com/actuator/health | Overall health check |
| Backend Redis Health | https://s2dcms-backend.onrender.com/health/redis | Redis connection status |
| Prometheus Metrics | https://s2dcms-backend.onrender.com/actuator/prometheus | Metrics scrape |
| Frontend | https://student-complaints-tau.vercel.app | React SPA |
| Render Dashboard | https://dashboard.render.com | Backend hosting |
| Vercel Dashboard | https://vercel.com | Frontend hosting |
| Supabase Dashboard | https://app.supabase.com | Database |
| Redis Cloud | https://app.redislabs.com | Cache |
| CloudAMQP | https://www.cloudamqp.com | Message queue |
| Brevo Dashboard | https://app.brevo.com | Email service |

---

## Health Checks

### Quick Health Check

```bash
# Overall health (should return {"status":"UP"})
curl https://s2dcms-backend.onrender.com/actuator/health

# Redis health
curl https://s2dcms-backend.onrender.com/health/redis

# Prometheus metrics
curl https://s2dcms-backend.onrender.com/actuator/prometheus | head -30
```

### What "UP" Means

| Component | Expected Status | What DOWN means |
|-----------|----------------|-----------------|
| `postgres` | UP | Database unreachable; check Supabase status |
| `redis` | UP | Redis unreachable; caching degraded but app still works |

---

## Common Incidents

### 1. Backend Returns 503 or Won't Start

**Symptoms:** `/actuator/health` returns DOWN or connection refused.

**Diagnosis:**
```bash
# Check Render deployment status
# Visit: https://dashboard.render.com

# Check recent logs
# In Render dashboard: Logs tab for the backend service

# Check if Flyway migrations succeeded
# Look for "Flyway" in Render build logs
```

**Common Causes:**
- Supabase connection pool exhausted (session mode instead of transaction mode)
  - Fix: Ensure `SPRING_DATASOURCE_URL` uses port `6543` with `prepareThreshold=0`
- Missing environment variables on Render
  - Fix: Check Render dashboard → Environment → all required vars present
- Render free tier spun down (cold start)
  - Fix: UptimeRobot pings `/health` every 5 minutes; first request after sleep is slow

**Resolution:**
1. Check Render dashboard for build/deploy failures
2. Verify all environment variables are set
3. Trigger manual redeploy from Render dashboard if needed
4. If Supabase pool exhausted, switch to transaction pooler (port 6543)

---

### 2. Emails Not Being Sent

**Symptoms:** Users report not receiving verification/password reset emails.

**Diagnosis:**
```bash
# Check RabbitMQ queue depth
# Visit: CloudAMQP dashboard → Queues → email.queue

# Check DLQ for dead letters
# Visit: CloudAMQP dashboard → Queues → email.queue.dlq

# Check backend logs for Brevo errors
# Search Render logs for "Brevo" or "email"
```

**Common Causes:**
- Brevo API key invalid or rate-limited
- RabbitMQ consumer not running
- Redis dedup key blocking redelivery (1-hour TTL)

**Resolution:**
1. Verify `BREVO_API_KEY` is set correctly on Render
2. Check Brevo dashboard for API usage/limits
3. Purge DLQ if messages are stuck
4. Wait for Redis dedup TTL (1 hour) or flush Redis keys if urgent

---

### 3. AI Features Not Working (Summarization/Replies)

**Symptoms:** AI complaint summary or reply suggestions return errors.

**Diagnosis:**
```bash
# Check Groq circuit breaker state
curl https://s2dcms-backend.onrender.com/actuator/metrics/resilience4j.circuitbreaker.state

# Check backend logs for Groq errors
# Search Render logs for "Groq" or "AI"
```

**Common Causes:**

---

## Deployment

### Deploying Backend Changes

1. Push code to `main` branch on GitHub (or open a PR to `main`).
2. GitHub Actions CI runs automatically on push/PR:
   - `test` job: unit + integration tests (`mvn test -B`, H2-backed test config; CI provisions PostgreSQL/Redis/RabbitMQ services), then builds/uploads the JAR.
3. `deploy` runs on `main` pushes (not PRs) after `test` passes and triggers the Render deploy hook. **`deploy` depends on `test` only** (functional correctness is the release gate).
4. Render pulls the latest commit/image, runs Flyway migrations, and starts the app.
5. Verify: `curl https://s2dcms-backend.onrender.com/actuator/health` returns UP.

### Manual k6 Load Test (on-demand, read-only)

k6 is **not** part of automatic push/PR CI. It is a manual performance check against a live target.

1. In GitHub → Actions → **CI Pipeline** → **Run workflow** (`workflow_dispatch`).
2. The manual run executes `test`, then `load-test` (`needs: test`, `if: workflow_dispatch`) which installs k6 and runs `k6/load-test.js` with `BASE_URL/TEST_EMAIL/TEST_PASSWORD` from secrets.
3. The script is read-only (login + authenticated `GET /api/auth/me`), enforces latency/error thresholds, and does **not** modify code, app state, or deployment. A pass/fail is feedback only; deploy is not gated on k6.
4. Caveat: a manual `workflow_dispatch` run from `main` can still trigger the `deploy` job (it requires only `test`) and redeploy the same commit via the hook; k6 outcome does not control this.

### Manual Render Redeploy

If CI/CD is broken, manually trigger redeploy:
1. Visit https://dashboard.render.com
2. Select `s2dcms-backend` service
3. Click "Manual Deploy" → "Deploy latest commit"

### Required Render Environment Variables

| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://...:6543/...?prepareThreshold=0` |
| `SPRING_DATASOURCE_USERNAME` | Supabase DB username |
| `SPRING_DATASOURCE_PASSWORD` | Supabase DB password |
| `SPRING_DATA_REDIS_HOST` | Redis Cloud host |
| `SPRING_DATA_REDIS_PORT` | Redis Cloud port |
| `SPRING_DATA_REDIS_PASSWORD` | Redis Cloud password |
| `SPRING_RABBITMQ_HOST` | CloudAMQP host |
| `SPRING_RABBITMQ_PORT` | 5672 |
| `SPRING_RABBITMQ_USERNAME` | CloudAMQP username |
| `SPRING_RABBITMQ_PASSWORD` | CloudAMQP password |
| `SPRING_AI_OPENAI_API_KEY` | Groq API key |
| `BREVO_API_KEY` | Brevo API key |
| `BREVO_SENDER_EMAIL` | Sender email address |
| `ADMIN_EMAIL` | Admin notification email |
| `JWT_SECRET` | At least 32 chars |
| `SUPABASE_URL` | Supabase project URL |
| `SUPABASE_SERVICE_ROLE_KEY` | Supabase service role key |
| `SUPABASE_STORAGE_BUCKET` | `s2dcms-uploads` |
| `ENVIRONMENT` | `production` |
| `SPRING_PROFILES_ACTIVE` | `prod` |

---

## Local Development

### Start Stack with Docker Compose

```bash
cd S2dcms-backend

# Copy env example
cp .env.example .env
# Edit .env with your values

# Start all services
docker-compose up -d

# Check health
curl http://localhost:8080/actuator/health

# Stop all services
docker-compose down
```

### Run Tests

```bash
cd S2dcms-backend
mvn test
```

### Run Load Test Locally

```bash
cd S2dcms-backend
k6 run k6/load-test.js
```

---

## Escalation

| Severity | Definition | Response Time | Escalate To |
|----------|-----------|---------------|-------------|
| P1 - Critical | Backend completely down, no API access | 15 min | @backend-oncall |
| P2 - High | Core features broken (login, complaints) | 1 hour | @backend-oncall |
| P3 - Medium | Non-core features broken (AI, file uploads) | 4 hours | @backend-secondary |
| P4 - Low | Cosmetic issues, minor bugs | Next sprint | #s2dcms-dev |

### Escalation Steps

1. Page @backend-oncall in Slack (#s2dcms-alerts)
2. If no response in 15 min, page @backend-secondary
3. Create incident channel: #incident-s2dcms-[date]
4. Post status updates every 30 minutes during P1/P2 incidents
5. Write post-incident review within 24 hours for P1/P2

---

## Useful Commands

```bash
# Check backend health
curl https://s2dcms-backend.onrender.com/actuator/health

# Check circuit breakers
curl https://s2dcms-backend.onrender.com/actuator/metrics/resilience4j.circuitbreaker.state

# Check Redis health
curl https://s2dcms-backend.onrender.com/health/redis

# Clear Supabase idle connections
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE application_name = 'PostgreSQL JDBC Driver' AND state = 'idle';
```

---

## On-Call Handoff Checklist

- [ ] All P1/P2 incidents acknowledged
- [ ] Current deploy status (any in-flight deploys?)
- [ ] Known issues or recent changes
- [ ] Pending alerts from UptimeRobot
- [ ] Circuit breaker states (brevo, groq)
- [ ] Credentials/keys expiring soon (JWT_SECRET, API keys)
- Groq API key invalid or exhausted
- Circuit breaker OPEN (too many failures)
- Rate limiting on Groq free tier

**Resolution:**
1. Verify `SPRING_AI_OPENAI_API_KEY` is set correctly on Render
2. Check Groq dashboard for API usage
3. Wait for circuit breaker to transition to HALF_OPEN (30s wait)
4. If breaker stays OPEN, investigate root cause in logs

---

### 4. File Uploads Not Working

**Symptoms:** Profile pictures or complaint attachments fail to upload.

**Diagnosis:**
```bash
# Check Supabase Storage bucket
# Visit: Supabase Dashboard → Storage → s2dcms-uploads

# Check backend logs for upload errors
# Search Render logs for "upload" or "file"
```

**Common Causes:**
- Supabase Storage not configured (production required)
- `SUPABASE_SERVICE_ROLE_KEY` missing
- File exceeds 5MB limit
- Invalid MIME type

**Resolution:**
1. Verify `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` on Render
2. Ensure Supabase Storage bucket `s2dcms-uploads` exists
3. Check file size (< 5MB) and type (PNG, JPG, JPEG, PDF, DOC, DOCX)
4. Note: Local filesystem uploads do NOT survive Render redeploys

---

### 5. Rate Limiting Blocking Users

**Symptoms:** Users get "Too many attempts" errors on login.

**Diagnosis:**
- Rate limit: 4 failed attempts per hour per email+action
- Cooldown: 60 minutes
- Check `UserActionLimitRepository` in PostgreSQL

**Resolution:**
1. Wait for cooldown period to expire (60 minutes)
2. Or manually clear rate limit rows from database (Supabase SQL editor)
3. Educate users on correct credentials

---

### 6. High Latency / Timeouts

**Symptoms:** API responses slow (> 2 seconds) or timing out.

**Diagnosis:**
```bash
# Check Prometheus metrics for p95 latency
curl https://s2dcms-backend.onrender.com/actuator/metrics/http.server.requests

# Check Render service metrics
# Visit: Render dashboard → Metrics tab
```

**Common Causes:**
- Render free tier cold start
- Supabase connection pool saturated
- Groq AI calls taking up to 35s (3 attempts)
- Redis unreachable (falling through to DB)

**Resolution:**
1. Wait for Render instance to warm up
2. Check Supabase pool usage; consider upgrading plan
3. Groq calls are async; complaint submission doesn't block on AI
4. Verify Redis connectivity

---

### 7. Redis Health Degraded

**Symptoms:** `/health/redis` returns DOWN or warnings.

**Diagnosis:**
- `RedisHealthScheduler` PINGs Redis every 5 minutes
- Check Redis Cloud dashboard for instance status
- Check `heartbeat:last_ping` key in Redis

**Resolution:**
1. Verify Redis Cloud instance is running
2. Check `SPRING_DATA_REDIS_HOST`, `PORT`, `PASSWORD` on Render
3. Redis is non-critical; app falls back to PostgreSQL if cache unavailable
| `rabbit` | UP | RabbitMQ unreachable; email queue backed up |
| `brevo` circuit breaker | CLOSED | Brevo API failing; emails may not send |
| `groq` circuit breaker | CLOSED | Groq API failing; AI features unavailable |

### Circuit Breaker Check

```bash
curl https://s2dcms-backend.onrender.com/actuator/metrics/resilience4j.circuitbreaker.state
```

Breaker states: `CLOSED` (normal), `OPEN` (failing fast), `HALF_OPEN` (testing recovery).