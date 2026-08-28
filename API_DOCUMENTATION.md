# S2DCMS API Documentation
## Student to Department Complaint Management System

**Base URL:** `http://localhost:8080/api`

**Authentication:** Bearer Token (JWT) - Include `Authorization: Bearer <access_token>` header for protected endpoints

**Token Management:**
- Access tokens are short-lived JWT tokens (15 min expiry)
- Refresh tokens are stored in Redis and used to obtain new access tokens
- **Refresh Token Rotation**: Advanced session management with automatic rotation - maintains maximum 4 active sessions per user, automatically invalidating oldest tokens when new sessions are created
- **Automated Token Cleanup**: Scheduled daily cleanup of expired and revoked tokens to optimize database performance
- Use `/api/auth/refresh-token` to get new access tokens

---

## Authentication Endpoints

### POST /api/auth/login
Login user (student or department)

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Response (200):**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

---

### POST /api/auth/refresh-token
Refresh access token using refresh token

**Request Body:**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response (200):**
```json
{
  "accessToken": "new_access_token_here",
  "refreshToken": "new_refresh_token_here"
}
```

---

### POST /api/auth/logout
Logout user and invalidate refresh token

**Request Body:**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response (204):** No Content

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

**Response (200):** OK

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

### POST /api/ai/summarize
Summarize a complaint using AI (requires authentication)

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

---

### POST /api/ai/suggest-reply
Get AI-suggested reply for department staff (requires authentication)

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

---

### POST /api/ai/write-complaint
Help student write a formal complaint using AI (requires authentication)

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

**400 Bad Request:** Invalid input data
**401 Unauthorized:** Missing or invalid authentication token
**403 Forbidden:** User doesn't have permission for the resource
**404 Not Found:** Resource not found
**429 Too Many Requests:** Rate limit exceeded
**500 Internal Server Error:** Server error

---

## Frontend Implementation Notes

### Authentication Flow
1. User logs in via `/api/auth/login` → receives access & refresh tokens
2. Store access token in memory/state (not localStorage for security)
3. Store refresh token securely (httpOnly cookie recommended)
4. Use access token in Authorization header for API calls
5. When access token expires, call `/api/auth/refresh-token` with refresh token
6. New tokens are returned - update stored tokens
7. On logout, call `/api/auth/logout` to invalidate refresh token

### Token Rotation
- Refresh tokens rotate on each use (old token becomes invalid)
- Maximum 4 active sessions per user - oldest token invalidated when new session created
- Backend automatically clears expired and revoked refresh tokens daily
- Always use the latest refresh token from refresh response

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
