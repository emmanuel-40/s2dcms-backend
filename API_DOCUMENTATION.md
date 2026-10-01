# S2DCMS API Documentation
## Student to Department Complaint Management System

**Base URL (local):** `http://localhost:8080/api`
**Base URL (deployed):** `https://s2dcms-backend.onrender.com/api`

**Authentication:** `HttpOnly` **session cookies** — there is no bearer-token mode. `Authorization: Bearer …` is not an accepted credential.

| Cookie |        Readable by JS |  Lifetime |        Purpose |
| 
| `accessToken` | No (`HttpOnly`) |  15 min |    validated by `JwtAuthFilter` on every request |
| `refreshToken` | No (`HttpOnly`) |  24 h |     rotates on refresh, revoked server-side in PostgreSQL 
| `XSRF-TOKEN` | Yes, by design |   session |     double-submit CSRF token, echoed back as `X-XSRF-TOKEN` 

**Request rules**
- Send cookies on every call: `fetch(url, { credentials: 'include' })` (Axios: `withCredentials: true`)
- `POST` / `PUT` / `PATCH` / `DELETE` require the `X-XSRF-TOKEN` header. Safe methods are exempt by default, and the pre-auth endpoints (`/api/auth/login`, `/api/auth/refresh-token`, `/api/auth/forgot-password`, `/api/auth/reset-password`, `/api/auth/logout`, `/api/students/auth/**`, `/api/department/auth/**`, `/api/contact/**`) are excluded because no session exists yet on those calls
- The token reaches a cross-origin SPA through channels `document.cookie` cannot: the CORS-exposed `X-XSRF-TOKEN` **response header** on every response, the JSON body of `GET /api/auth/csrf`, and the readable `XSRF-TOKEN` cookie when served same-origin. The SPA captures the header, bootstraps via `GET /api/auth/csrf` before its first write, and re-seeds + retries once if a write is answered `403` with a CSRF error
- No/expired session → `401` so the SPA can attempt a refresh; authenticated but not permitted → `403`; missing or stale CSRF token on an authenticated request → `403` with `{"error": "CSRF token missing or invalid"}`
- Cookies are `SameSite=Lax` locally and `SameSite=None; Secure` when the backend runs with `ENVIRONMENT=production`

**Token management**
- Access tokens are short-lived JWTs (15 min) delivered **only** as `HttpOnly` cookies; `AuthResponse` has just `email` and `role` — there is no token field on the DTO for a token to leak through
- Refresh tokens are persisted in **PostgreSQL** (`refresh_token` table), so sessions survive restarts — they are *not* kept in Redis
- **Rotation:** each refresh invalidates the previous token; every account holds at most **4 live sessions**, oldest evicted first
- **Cleanup:** a scheduled job (`0 0 */5 * * *`) deletes expired and revoked rows every 5 hours
- Changing a password revokes **all** active sessions for that account
- Refresh with `POST /api/auth/refresh-token` (reads the cookie; no body required)

---

## Authentication Endpoints

### GET /api/auth/csrf

Bootstrap the double-submit CSRF token. A deployed SPA cannot read another origin's cookies with `document.cookie`, so the token is served in this JSON body as well (the same value is returned in the CORS-exposed `X-XSRF-TOKEN` response header). Safe method — no CSRF header required, callable before login. The SPA calls this before its first state-changing request and again after a `403` CSRF rejection.

**Response (200):**
```json
{ "token": "f47ac10b-58cc-4372-a567-0e02b2c3d479" }
```

### POST /api/auth/login
Log in as a student, department, or admin. No CSRF token or cookie is required — this is a pre-auth endpoint.

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Response (200):** the tokens are **not** in this body — `AuthResponse` has only two fields, and the credentials arrive as `HttpOnly` cookies
```json
{
  "email": "user@example.com",
  "role": "STUDENT"
}
```

```http
Set-Cookie: accessToken=<jwt>; Max-Age=900; Path=/; HttpOnly; SameSite=Lax
Set-Cookie: refreshToken=<opaque-uuid>; Max-Age=86400; Path=/; HttpOnly; SameSite=Lax
```

**Errors:** `401` invalid credentials · `403` email not verified · `429` attempt cap reached (message includes the remaining cooldown)

---

### POST /api/auth/refresh-token
Exchange the `refreshToken` cookie for a fresh cookie pair.

**Request:** none. No body, no headers beyond the cookies the browser already sends — `POST /api/auth/refresh-token` with `credentials: 'include'` is the entire call. The old `RefreshTokenRequest` body DTO has been deleted; any payload you send is ignored by Spring.

**Response (200):** with two fresh `Set-Cookie` headers
```json
{
  "email": "user@example.com",
  "role": "STUDENT"
}
```

The previous refresh token is rotated out immediately.

**Errors:** `401` / `4xx` when the cookie is missing, unknown, revoked, or expired — the client must sign in again

---

### POST /api/auth/logout
Revoke the current session and clear both cookies.

**Request:** cookies only; no body. `credentials: 'include'` is enough (the endpoint reads the `refreshToken` cookie and never a payload)

**Response (204):** empty body, plus `Set-Cookie` headers with `Max-Age=0` for `accessToken` and `refreshToken`

---

### POST /api/auth/forgot-password
Initiate password reset (sends email with reset token)

**Request Body:**
```json
{
  "email": "user@example.com"
}
```

**Response (204):** No Content

---

### POST /api/auth/reset-password
Reset password using token from email

**Request Body:**
```json
{
  "token": "reset_token_from_email",
  "newPassword": "newPassword123"
}
```

**Response (204):** No Content

---

### POST /api/user/change-password
Change password for authenticated user (requires authentication)

**Request Body:**
```json
{
  "oldPassword": "oldPassword123",
  "newPassword": "newPassword123"
}
```

**Response (200):** OK — every active session for the account is revoked, so the client must sign in again

---

### GET /api/auth/me
Return the identity behind the current session cookie. The SPA calls this on load to decide whether someone is signed in, and it is also the request that seeds the `XSRF-TOKEN` cookie.

**Response (200):**
```json
{
  "email": "student@example.com",
  "role": "STUDENT"
}
```

**Response (401):** `{"error":"Unauthorized"}` when no valid `accessToken` cookie is present

---

## Student Endpoints

### POST /api/students/auth/register
Register new student account

**Request Body:**
```json
{
  "name": "John Doe",
  "regNo": "REG2024001",
  "email": "student@example.com",
  "password": "password123",
  "departmentId": 1
}
```

**Response (200):** "Registration successful. Check your email for verification link."

---

### GET /api/students/auth/verify
Verify student email using token from email

**Query Parameters:**
- `token` (string) - Verification token from email

**Response (200):** "Email verified successfully."

---

### POST /api/students/auth/resend-verification
Resend verification email

**Request Body:**
```json
{
  "email": "student@example.com"
}
```

**Response (200):** "Verification email resent."

---

### GET /api/students/profile
Get current student profile (requires authentication)

**Response (200):**
```json
{
  "name": "John Doe",
  "regNo": "REG2024001",
  "email": "student@example.com",
  "departmentName": "Computer Science",
  "profilePicturePath": "/uploads/profiles/student_123.jpg",
  "emailVerified": true
}
```

---

### PUT /api/students/profile
Update student profile (requires authentication)

**Request:** `multipart/form-data`
- `name` (string) - Student name
- `image` (file, optional) - Profile picture

**Response (200):**
```json
{
  "name": "John Updated",
  "regNo": "REG2024001",
  "email": "student@example.com",
  "departmentName": "Computer Science",
  "profilePicturePath": "/uploads/profiles/student_123_updated.jpg",
  "emailVerified": true
}
```

---

### POST /api/students/complaints
Submit a new complaint (requires authentication)

**Request:** `multipart/form-data`
- `title` (string) - Complaint title
- `content` (string) - Complaint content
- `attachment` (file, optional) - Supporting document/image

**Response (200):**
```json
{
  "id": 1,
  "profilePicturePath": "/uploads/profiles/student_123.jpg",
  "title": "Complaint Title",
  "content": "Complaint content here",
  "reply": null,
  "status": "PENDING",
  "attachmentPath": "/uploads/attachments/complaint_1.pdf",
  "replyAttachmentPath": null,
  "sentAt": "2024-01-15T10:30:00",
  "repliedAt": null,
  "departmentProfile": "/uploads/profiles/dept_1.jpg",
  "studentName": null,
  "studentRegNumber": null,
  "departmentName": "Computer Science"
}
```

---

### GET /api/students/complaints
Get student's complaint history (requires authentication)

**Query Parameters:**
- `status` (string, optional) - Filter by status: "ALL", "PENDING", "IN_PROGRESS", "REPLIED", "CLOSED" (default: "ALL")
- `sort` (string, optional) - Sort order: "NEWEST", "OLDEST" (default: "NEWEST")
- `page` (integer, optional) - Page number (default: 0)
- `size` (integer, optional) - Page size (default: 10)

**Response (200):**
```json
{
  "content": [
    {
      "id": 1,
      "profilePicturePath": "/uploads/profiles/student_123.jpg",
      "snippet": "Complaint content here truncated to 40 chars...",
      "status": "PENDING",
      "sentAt": "2024-01-15T10:30:00",
      "seenByDepartment": false,
      "seenByStudent": true
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 25,
    "totalPages": 3
  }
}
```

---

### GET /api/students/complaints/{id}
Get specific complaint details (requires authentication)

**Path Parameters:**
- `id` (long) - Complaint ID

**Response (200):**
```json
{
  "id": 1,
  "profilePicturePath": "/uploads/profiles/student_123.jpg",
  "title": "Complaint Title",
  "content": "Full complaint content here",
  "reply": "Department response here",
  "status": "REPLIED",
  "attachmentPath": "/uploads/attachments/complaint_1.pdf",
  "replyAttachmentPath": "/uploads/replies/reply_1.pdf",
  "sentAt": "2024-01-15T10:30:00",
  "repliedAt": "2024-01-16T14:20:00",
  "departmentProfile": "/uploads/profiles/dept_1.jpg",
  "studentName": null,
  "studentRegNumber": null,
  "departmentName": "Computer Science"
}
```

---

## Department Endpoints

### GET /api/department/all
Public list of departments, used by the registration screen to populate the department dropdown. `ADMIN` accounts are filtered out.

**Response (200):**
```json
[
  {
    "id": 1,
    "departmentName": "Computer Science",
    "email": "cs.department@university.edu",
    "departmentProfile": "/uploads/profiles/dept_1.jpg"
  }
]
```

---

### GET /api/department/profile
Get department profile (requires authentication)

**Response (200):**
```json
{
  "id": 1,
  "departmentProfile": "/uploads/profiles/dept_1.jpg",
  "departmentName": "Computer Science",
  "email": "cs.department@university.edu"
}
```

---

### PUT /api/department/profile
Update department profile (requires authentication)

**Request:** `multipart/form-data`
- `image` (file, optional) - Department profile picture

**Response (200):**
```json
{
  "id": 1,
  "departmentProfile": "/uploads/profiles/dept_1_updated.jpg",
  "departmentName": "Computer Science",
  "email": "cs.department@university.edu"
}
```

---

### GET /api/department/complaints
Get complaints assigned to department (requires authentication)

**Query Parameters:**
- `status` (string, optional) - Filter by status: "ALL", "PENDING", "IN_PROGRESS", "REPLIED", "CLOSED" (default: "ALL")
- `sort` (string, optional) - Sort order: "NEWEST", "OLDEST" (default: "NEWEST")
- `page` (integer, optional) - Page number (default: 0)
- `size` (integer, optional) - Page size (default: 10)

**Response (200):**
```json
{
  "content": [
    {
      "id": 1,
      "profilePicturePath": "/uploads/profiles/student_123.jpg",
      "snippet": "Complaint content here truncated...",
      "status": "PENDING",
      "sentAt": "2024-01-15T10:30:00",
      "seenByDepartment": false,
      "seenByStudent": true
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 50,
    "totalPages": 5
  }
}
```

---

### GET /api/department/complaints/{id}
Get specific complaint details with student info (requires authentication)

**Path Parameters:**
- `id` (long) - Complaint ID

**Response (200):**
```json
{
  "id": 1,
  "profilePicturePath": "/uploads/profiles/student_123.jpg",
  "title": "Complaint Title",
  "content": "Full complaint content here",
  "reply": null,
  "status": "PENDING",
  "attachmentPath": "/uploads/attachments/complaint_1.pdf",
  "replyAttachmentPath": null,
  "sentAt": "2024-01-15T10:30:00",
  "repliedAt": null,
  "departmentProfile": "/uploads/profiles/dept_1.jpg",
  "studentName": "John Doe",
  "studentRegNumber": "REG2024001",
  "departmentName": null
}
```

---

### POST /api/department/reply
Reply to a complaint (requires authentication)

**Request:** `multipart/form-data`
- `messageId` (long) - Complaint ID
- `reply` (string) - Reply content
- `attachment` (file, optional) - Supporting document/image

**Response (200):**
```json
{
  "id": 1,
  "profilePicturePath": "/uploads/profiles/student_123.jpg",
  "title": "Complaint Title",
  "content": "Full complaint content here",
  "reply": "Department response here",
  "status": "REPLIED",
  "attachmentPath": "/uploads/attachments/complaint_1.pdf",
  "replyAttachmentPath": "/uploads/replies/reply_1.pdf",
  "sentAt": "2024-01-15T10:30:00",
  "repliedAt": "2024-01-16T14:20:00",
  "departmentProfile": "/uploads/profiles/dept_1.jpg",
  "studentName": "John Doe",
  "studentRegNumber": "REG2024001",
  "departmentName": null
}
```

---

### PUT /api/department/complaints/{complaintId}/close
Close a complaint (requires authentication)

**Path Parameters:**
- `complaintId` (long) - Complaint ID

**Response (200):**
```json
{
  "id": 1,
  "profilePicturePath": "/uploads/profiles/student_123.jpg",
  "title": "Complaint Title",
  "content": "Full complaint content here",
  "reply": "Department response here",
  "status": "CLOSED",
  "attachmentPath": "/uploads/attachments/complaint_1.pdf",
  "replyAttachmentPath": "/uploads/replies/reply_1.pdf",
  "sentAt": "2024-01-15T10:30:00",
  "repliedAt": "2024-01-16T14:20:00",
  "departmentProfile": "/uploads/profiles/dept_1.jpg",
  "studentName": "John Doe",
  "studentRegNumber": "REG2024001",
  "departmentName": null
}
```

---

## AI Endpoints

All three endpoints call Groq synchronously through Spring `RestClient` and return the model's text
as a **`text/plain`** body. Failures are classified server-side into a status a client can act on,
always with a generic `text/plain` sentence — never the provider's own message, so no AI error can
reveal the provider, the quota, or the key state:

| Status |               Meaning |                                                Extra header |

| `403` | caller lacks the required role, or the CSRF header is missing | — |
| `429` | the provider is rate limiting us; back off and retry | `Retry-After` (seconds; upstream value, else `30`) |
| `502` | our payload was rejected (provider `400`/`404`/`413`/`422`), or a 2xx came back with no usable message | — |
| `503` | the provider is down or unreachable, **or our own server-side key is rejected** | `Retry-After: 30` |
| `500` | anything unrecognised | — |

A `401` from the provider is deliberately reported as `503`, not `401`: it means *our* key is at
fault, so surfacing it would both leak credentials state and send the SPA into a pointless
refresh-token loop. Upstream statuses and bodies are logged server-side only (truncated).

### POST /api/ai/summarize
Summarize a complaint using AI (requires the `DEPARTMENT` role)

**Request Body:**
```json
{
  "text": "Full complaint text to summarize"
}
```

**Response (200):**
```json
"• Point 1 of summary\n• Point 2 of summary\n• Point 3 of summary"
```

**Errors:** `403` non-`DEPARTMENT` caller or missing CSRF token · `429`/`502`/`503`/`500` per the [AI error table](#ai-endpoints) (`429` and `503` carry `Retry-After`)

---

### POST /api/ai/suggest-reply
Get AI-suggested reply for department staff (requires the `DEPARTMENT` role)

**Request Body:**
```json
{
  "complaintText": "Full complaint text to respond to"
}
```

**Response (200):**
```json
"Suggested professional response here"
```

**Errors:** `403` non-`DEPARTMENT` caller or missing CSRF token · `429`/`502`/`503`/`500` per the [AI error table](#ai-endpoints) (`429` and `503` carry `Retry-After`)

---

### POST /api/ai/write-complaint
Help student write a formal complaint using AI (requires the `STUDENT` role)

**Request Body:**
```json
{
  "situation": "Describe the situation or issue"
}
```

**Response (200):**
```json
"TITLE: Complaint Title\nCONTENT: Formal complaint content here"
```

**Errors:** `403` non-`STUDENT` caller or missing CSRF token · `429`/`502`/`503`/`500` per the [AI error table](#ai-endpoints) (`429` and `503` carry `Retry-After`)

---

## Health Check Endpoint

### GET /health
Health check endpoint for uptime monitoring (no authentication required)

**Response (200):**
```json
{
  "status": "UP",
  "timestamp": "2024-01-15T10:30:00",
  "service": "S2DCMS Backend"
}
```

---

### GET /health/redis
Live Redis connectivity check, surfaced by the same dependency the `@Scheduled` health PING uses. Requires no authentication.

**Response (200):**
```json
{
  "status": "UP",
  "redis": "Connected",
  "ping": "PONG",
  "timestamp": "2024-01-15T10:30:00"
}
```

**Response (503):** when Redis cannot be reached
```json
{
  "status": "DOWN",
  "redis": "Disconnected",
  "error": "Unable to connect to Redis",
  "timestamp": "2024-01-15T10:30:00"
}
```

A failed check degrades the response instead of throwing, so cache outages never take the API down — reads simply fall through to PostgreSQL.

---

## Admin Endpoints

### POST /api/department/admin/create
Create a new department (requires ADMIN role)

**Request Body:**
```json
{
  "departmentName": "Computer Science",
  "email": "cs.department@university.edu",
  "password": "securePassword123",
  "departmentProfile": "/uploads/profiles/dept_cs.jpg"
}
```

**Response (200):**
```json
{
  "id": 1,
  "departmentName": "Computer Science",
  "email": "cs.department@university.edu",
  "departmentProfile": "/uploads/profiles/dept_cs.jpg"
}
```

---

### DELETE /api/department/admin/{id}
Delete a department (requires ADMIN role)

**Path Parameters:**
- `id` (long) - Department ID

**Response (204):** No Content

---

### PUT /api/department/admin/{id}/password
Update department password (requires ADMIN role)

**Path Parameters:**
- `id` (long) - Department ID

**Request Body:**
```json
{
  "newPassword": "newSecurePassword123"
}
```

**Response (204):** No Content

---

### GET /api/students/admin/all
Get all students (requires ADMIN role)

**Response (200):**
```json
[
  {
    "name": "John Doe",
    "regNo": "REG2024001",
    "email": "john.doe@student.edu",
    "departmentName": "Computer Science",
    "profilePicturePath": "/uploads/profiles/student_123.jpg",
    "emailVerified": true
  }
]
```

---

### DELETE /api/students/admin/{id}
Delete a student (requires ADMIN role)

**Path Parameters:**
- `id` (long) - Student ID

**Response (204):** No Content

---

## Public Contact Endpoint

### POST /api/contact
Submit public contact message (no authentication required)

**Request Body:**
```json
{
  "name": "Visitor Name",
  "email": "visitor@example.com",
  "message": "Contact message here"
}
```

**Response (200):** "Message sent successfully"

---

## Data Models & Enums

### Message Status Enum
- `PENDING` - Complaint submitted, awaiting department response
- `IN_PROGRESS` - Department is working on the complaint
- `REPLIED` - Department has responded
- `CLOSED` - Complaint is closed

### Common Response Fields

**MessagePreviewDto (for list views):**
- `id` (long) - Message ID
- `profilePicturePath` (string) - Student profile picture URL
- `snippet` (string) - First 40 characters of content
- `status` (string) - Message status
- `sentAt` (datetime) - When message was sent
- `seenByDepartment` (boolean) - Whether department has seen it
- `seenByStudent` (boolean) - Whether student has seen it

**MessageResponse (for detail views):**
- `id` (long) - Message ID
- `profilePicturePath` (string) - Student profile picture URL
- `title` (string) - Complaint title
- `content` (string) - Full complaint content
- `reply` (string) - Department reply (null if not replied)
- `status` (string) - Message status
- `attachmentPath` (string) - Student attachment URL (null if none)
- `replyAttachmentPath` (string) - Department reply attachment URL (null if none)
- `sentAt` (datetime) - When message was sent
- `repliedAt` (datetime) - When department replied (null if not replied)
- `departmentProfile` (string) - Department profile picture URL
- `studentName` (string) - Student name (only in department view)
- `studentRegNumber` (string) - Student registration number (only in department view)
- `departmentName` (string) - Department name (only in student view)

---

## File Upload Notes

- Profile pictures and attachments are uploaded as `multipart/form-data`
- File size limits apply (check backend configuration)
- Supported file types depend on backend configuration
- Files are stored in server uploads directory and accessible via URLs

---

## Error Responses

**400 Bad Request:** Invalid input data (Bean Validation failures name the offending fields)
**401 Unauthorized:** `{"error":"Unauthorized"}` — no valid `accessToken` cookie, or the refresh flow failed. The SPA treats this as "try refresh, then sign out"
**403 Forbidden:** `{"error":"Forbidden"}` — authenticated, but the role policy for that path does not allow it (this includes a missing or mismatched CSRF token)
**404 Not Found:** Resource not found
**429 Too Many Requests:** login / password-reset attempt cap reached; the message reports the remaining cooldown time
**500 Internal Server Error:** Server error

---

## Frontend Implementation Notes

### Authentication Flow
1. Call `GET /api/auth/me` on app start — `200` means the session cookie is still valid, `401` means "not signed in". This request also seeds the `XSRF-TOKEN` cookie
2. Log in through `POST /api/auth/login` with `credentials: 'include'`; the browser stores the `HttpOnly` `accessToken` / `refreshToken` cookies and the app keeps only `{ email, role }` in memory
3. Never write a token to `localStorage`/`sessionStorage`, and never build an `Authorization` header — the API does not accept one
4. Mirror the readable `XSRF-TOKEN` cookie into the `X-XSRF-TOKEN` header on every `POST`/`PUT`/`PATCH`/`DELETE`
5. On `401`, call `POST /api/auth/refresh-token` (cookie-only) and replay the original request once. Coalesce concurrent `401`s onto one shared promise so a burst of expired requests triggers exactly one refresh
6. If the refresh itself fails, drop the local user state and redirect to login — the session is gone
7. On logout, `POST /api/auth/logout` revokes the stored token row and the response clears both cookies with `Max-Age=0`

### Token Rotation
- Refresh tokens rotate on every refresh; the previous value stops working immediately
- At most 4 live sessions per account — creating a 5th evicts the oldest
- Expired and revoked rows are swept every 5 hours by a scheduled job
- Changing a password revokes every session for that account at once

### File Uploads
- Use FormData for file uploads
- Include other form fields as FormData parameters
- Set appropriate Content-Type header (browser sets automatically for FormData)

### Pagination
- Use `page` and `size` query parameters
- Response includes pagination metadata in `pageable` object
- Default page size is 10, adjust as needed for UI

### Status Filtering
- Use status parameter to filter complaints: "ALL", "PENDING", "IN_PROGRESS", "REPLIED", "CLOSED"
- Use sort parameter: "NEWEST" (default) or "OLDEST"
