package com.myproject.S2dcms.Exception;

import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.TypeMismatchException;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@RestControllerAdvice
public class GlobalExceptionHandler {


    private static final org.slf4j.Logger logger= LoggerFactory.getLogger(GlobalExceptionHandler.class);


    private ResponseEntity<Map<String, Object>> buildResponse(String message, HttpServletRequest request,
                                                              HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
                body.put("timestamp", LocalDateTime.now());
                body.put("status", status.value());
                body.put("error", message);
                body.put("path", request.getRequestURI());

        return new  ResponseEntity<>(body,status);
    }

    // ==================== Student ====================
    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<Map<String, Object>> handleRegistration(RegistrationException ex, HttpServletRequest request) {
        logger.warn("Registration failed", ex);
        return buildResponse(ex.getMessage(), request, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(StudentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleStudentNotFound(StudentNotFoundException ex, HttpServletRequest request) {
        logger.warn("Student not found",ex);
        return buildResponse("Student not found", request, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException ex, HttpServletRequest request) {
        logger.warn("Too many attempts. Please try again after 1 hour.",ex);
        return buildResponse("Too many attempts. Please try again after 1 hour.", request, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        logger.warn("File exceeds 5MB limit.",ex);
        return buildResponse("File exceeds 5MB limit.", request, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleStudentNotFound(TypeMismatchException ex, HttpServletRequest request) {
        logger.warn("Type mismatched",ex);
        return buildResponse("Type mismatched", request, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPassword(InvalidPasswordException ex, HttpServletRequest request) {
        logger.warn("Invalid credentials",ex);
        return buildResponse("Invalid credentials", request, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(ResendVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleResendVerification(ResendVerificationException ex, HttpServletRequest request) {
        logger.warn("Failed to resend verification",ex);
        return buildResponse("Failed to send verification", request, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ==================== Department ====================
    @ExceptionHandler(DepartmentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDepartmentNotFound(DepartmentNotFoundException ex, HttpServletRequest request) {
        logger.warn("Department not found",ex);
        return buildResponse("Department not found", request, HttpStatus.NOT_FOUND);
    }

    // ==================== Messages / Complaints ====================
    @ExceptionHandler(MessageNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotFound(MessageNotFoundException ex, HttpServletRequest request) {
        logger.warn("Message not found",ex);
        return buildResponse("Message not found", request, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ComplaintReplyException.class)
    public ResponseEntity<Map<String, Object>> handleComplaintReply(ComplaintReplyException ex, HttpServletRequest request) {
        logger.warn("Reply not sent",ex);
        return buildResponse("Reply not sent", request, HttpStatus.BAD_REQUEST);
    }

    // ==================== File / Upload ====================
    @ExceptionHandler(FileUploadException.class)
    public ResponseEntity<Map<String, Object>> handleFileUpload(FileUploadException ex, HttpServletRequest request) {
        logger.warn("Not sent",ex);
        return buildResponse("Not sent", request, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ==================== Refresh Token / Auth ====================
    @ExceptionHandler(RefreshTokenException.class)
    public ResponseEntity<Map<String, Object>> handleRefreshToken(RefreshTokenException ex, HttpServletRequest request) {
        logger.warn("Invalid or expired token",ex);
        return buildResponse("Invalid or expired token", request, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(EmailVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleEmailVerification(EmailVerificationException ex, HttpServletRequest request) {
        logger.warn("Email not verified",ex);
        return buildResponse("Email not verified", request, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(EmailAlreadyVerifiedException.class)
    public ResponseEntity<Map<String, Object>> handleEmailAlreadyVerified(EmailAlreadyVerifiedException ex, HttpServletRequest request) {
        logger.warn("Email already verified",ex);
        return buildResponse("Email already verified", request, HttpStatus.UNAUTHORIZED);
    }

    // ==================== Catch-all ====================
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        logger.warn("Something went wrong, please try again later",ex);
        return buildResponse("Something went wrong, please try again later", request, HttpStatus.BAD_REQUEST);
    }
}
