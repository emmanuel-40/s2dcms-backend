# S2DCMS Backend

Spring Boot backend API for the Student to Department Complaint Management System.

## Related Repositories

- **Frontend**: [s2dcms-frontend](https://github.com/emmanuel-40/s2dcms-frontend) - React frontend with Vite and Tailwind CSS

## License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## Overview

This backend provides REST APIs for student and department authentication, complaint management, file uploads, and messaging with JWT-based security, Redis caching, and RabbitMQ email processing.


# Features

## Authentication & Security
- **JWT Authentication System**: Secure token-based authentication with access and refresh tokens
- **Refresh Token Rotation**: Advanced token management with automatic rotation - maintains maximum 4 active sessions per user, automatically invalidating oldest tokens when new sessions are created
- **Automated Token Cleanup**: Scheduled daily cleanup of expired and revoked tokens to optimize database performance
- **Role-Based Authorization**: Granular access control with STUDENT, DEPARTMENT, and ADMIN roles
- **Secure Password Hashing**: BCrypt encryption for secure password storage
- **Rate Limiting Protection**: Brute-force attack prevention with configurable attempt limits

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
- File Size Restrictions
- MIME Type Validation

## Performance & Optimization
- **Redis Caching**: Configured for caching with 10-minute TTL (currently available for future use)
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

This project uses JWT-based authentication.

Protected endpoints require:

```text
Authorization: Bearer <token>
```


# File Uploads

Supported file types:
- PNG
- JPG
- JPEG
- PDF
- DOC
- DOCX

Maximum upload size:
- 20MB


# Architecture Highlights

- **Layered Architecture**: Controller → Service → Repository pattern for clean separation of concerns
- **DTO-based API responses**: Clean separation between internal models and API contracts
- **Service-oriented design**: Business logic encapsulated in service layer
- **Advanced token management**: Refresh token rotation with session limits (max 4 active sessions) and automated daily cleanup
- **Database-based token storage**: PostgreSQL persistence for refresh tokens (survives server restarts)
- **Database-based rate limiting**: PostgreSQL tracking of login attempts and cooldown periods
- **Redis caching**: Configured for caching with 10-minute TTL (available for future optimization)
- **Secure file handling**: Validation, size limits, and secure storage
- **Role-based endpoint protection**: Spring Security with custom JWT authentication filters
- **Async email processing**: RabbitMQ message queue for email operations
- **Database migrations**: Flyway for version-controlled schema changes
- **Database optimization**: Strategic indexing on frequently accessed columns for query performance
- **AI Integration**: Direct RestClient calls to Groq API for complaint summarization and reply suggestions

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
                              │ JWT Auth
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Backend (Spring Boot)                        │
│                    http://localhost:8080                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Security Layer (Spring Security)            │   │
│  │  - JWT Authentication Filter                             │   │
│  │  - Role-Based Access Control (STUDENT/DEPARTMENT/ADMIN)  │   │
│  │  - Rate Limiting                                         │   │
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
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Repository Layer (JPA)                      │   │
│  │  - StudentRepo                                           │   │
│  │  - DepartmentRepo                                        │   │
│  │  - MessageRepo                                           │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│   PostgreSQL     │  │     Redis        │  │    RabbitMQ      │
│   (Database)     │  │   (Cache)        │  │  (Email Queue)   │
│  localhost:5432  │  │  localhost:6379  │  │  localhost:5672  │
└──────────────────┘  └──────────────────┘  └──────────────────┘
                              │
                              │
                              ▼
                    ┌──────────────────┐
                    │    Groq AI API   │
                    │  (AI Services)   │
                    └──────────────────┘
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

# Redis (Redis Cloud)
SPRING_DATA_REDIS_HOST=your-redis-host
SPRING_DATA_REDIS_PORT=your-redis-port
SPRING_DATA_REDIS_PASSWORD=your-redis-password

# RabbitMQ (CloudAMQP)
SPRING_RABBITMQ_HOST=your-rabbitmq-host
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=your-username
SPRING_RABBITMQ_PASSWORD=your-password

# Groq AI
spring.ai.openai.api-key=your-groq-api-key
```

## Health Check Endpoint

The application includes a health check endpoint at `/health` for uptime monitoring:
- URL: https://s2dcms-backend.onrender.com/health
- Returns: `{"status":"UP","timestamp":"...","service":"S2DCMS Backend"}`
- Used by UptimeRobot to prevent backend cold starts on free tier

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