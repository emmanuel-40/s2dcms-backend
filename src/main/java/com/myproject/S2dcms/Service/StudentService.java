package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.*;
import com.myproject.S2dcms.dto.auth.ChangePasswordRequest;
import com.myproject.S2dcms.dto.email.EmailMessage;
import com.myproject.S2dcms.dto.message.MessagePreviewDto;
import com.myproject.S2dcms.dto.message.MessageResponse;
import com.myproject.S2dcms.dto.message.SendMessageRequest;
import com.myproject.S2dcms.dto.student.*;
import com.myproject.S2dcms.dto.verification.ForgotPasswordRequest;
import com.myproject.S2dcms.dto.verification.ResendEmailV;
import com.myproject.S2dcms.dto.verification.ResetPasswordRequest;
import com.myproject.S2dcms.model.*;
import com.myproject.S2dcms.repository.DepartmentRepo;
import com.myproject.S2dcms.repository.MessageRepo;
import com.myproject.S2dcms.repository.RefreshTokenRepository;
import com.myproject.S2dcms.repository.StudentRepo;
import com.myproject.S2dcms.securityConfig.JwtUtil;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import sendinblue.ApiException;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class StudentService {

    private final StudentRepo studentRepository;

    private final DepartmentRepo departmentRepository;

    private final MessageRepo complaintRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final UserActionService userActionService;

    private final PasswordEncoder passwordEncoder;

    private final JwtUtil jwtUtil;

    private final RefreshTokenService tokenService;

    private final FileStorageService fileStorageService;

    private final EmailProducerService emailProducerService;

    public StudentService(StudentRepo studentRepository, DepartmentRepo departmentRepository, MessageRepo complaintRepository, RefreshTokenRepository refreshTokenRepository, UserActionService userActionService, PasswordEncoder passwordEncoder, JwtUtil jwtUtil, RefreshTokenService tokenService, FileStorageService fileStorageService, EmailProducerService emailProducerService) {
        this.studentRepository = studentRepository;
        this.departmentRepository = departmentRepository;
        this.complaintRepository = complaintRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userActionService = userActionService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.tokenService = tokenService;
        this.fileStorageService = fileStorageService;
        this.emailProducerService = emailProducerService;
    }

    /*
       REGISTRATION */

    public void register(StudentRegisterRequest dto) {

        if (studentRepository.findByEmailIgnoreCase(dto.getEmail()).isPresent()) {
            throw new RegistrationException("Registration failed. Please check your information and try again.");
        }

        if (dto.getRegNo() == null || dto.getRegNo().trim().isEmpty()) {
            throw new RegistrationException("Registration number is required");
        }

        Department department = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> new DepartmentNotFoundException("Department not found"));

        Student student = new Student();
        student.setName(dto.getName());
        student.setEmail(dto.getEmail());
        student.setRegNo(dto.getRegNo());
        student.setPassword(passwordEncoder.encode(dto.getPassword()));
        student.setDepartment(department);
        student.setRole(Role.STUDENT);
        student.setEmailVerified(false);

        //generate token

        String token = UUID.randomUUID().toString();
        student.setVerificationToken(token);
        student.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));

        Student saved = studentRepository.save(student);

        EmailMessage emailMessage = new EmailMessage(
            saved.getEmail(),
            "Verify your email",
            "VERIFICATION",
            token,
            saved.getName()
        );
        emailProducerService.sendEmailMessage(emailMessage);

    }

    /*
       VERIFY EMAIL
     */
    public void verifyEmail(String token) {

        Student student = studentRepository.findByVerificationToken(token).orElseThrow(() -> new RefreshTokenException("Invalid token"));

        if (student.isEmailVerified()) {
            throw new EmailAlreadyVerifiedException("Email already verified");
        }
        if (student.getVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RefreshTokenException("Verification link expired");
        }

        student.setEmailVerified(true);
        student.setVerificationToken(null);
        student.setVerificationTokenExpiry(null);

        studentRepository.save(student);
    }

    /* =========================
       RESEND VERIFICATION
       ========================= */
    public void resendVerification(ResendEmailV resendEmailV) {

        userActionService.checkRateLimit(resendEmailV.getEmail(), "RESEND_VERIFICATION");

        Student student = studentRepository.findByEmailIgnoreCase(resendEmailV.getEmail())
                .orElseThrow(() -> new StudentNotFoundException("Student not found"));

        if (student.isEmailVerified()) {
            throw new EmailAlreadyVerifiedException("Email already verified");
        }

        String newToken = UUID.randomUUID().toString();

        student.setVerificationToken(newToken);
        student.setVerificationTokenExpiry(
                LocalDateTime.now().plusHours(24)
        );

        studentRepository.save(student);

        EmailMessage emailMessage = new EmailMessage(
            student.getEmail(),
            "Verify your email",
            "VERIFICATION",
            newToken,
            student.getName()
        );
        emailProducerService.sendEmailMessage(emailMessage);
    }


    /*
       SEND COMPLAINT (WITH ATTACHMENT)
     */
    @CacheEvict(value = {
            "messagesByStudent", "messageDetailsStudent",
            "DepartmentMessages", "messageDetailsDept"
    }, allEntries = true)
    public MessageResponse sendComplaint(
            String studentEmail,
            SendMessageRequest dto,
            MultipartFile attachment
    ) {

        Student student = studentRepository.findByEmailIgnoreCase(studentEmail)
                .orElseThrow(() -> new StudentNotFoundException("Student not found"));


        Message complaint = new Message();
        complaint.setTitle(dto.getTitle());
        complaint.setContent(dto.getContent());

        String attachmentPath = null;

        if (attachment != null && !attachment.isEmpty()) {
            attachmentPath = fileStorageService.storeAttachment(attachment);
        }
        complaint.setAttachmentPath(attachmentPath);
        complaint.setStatus(Message.Status.PENDING);
        complaint.setSentAt(LocalDateTime.now());
        complaint.setStudent(student);
        complaint.setDepartment(student.getDepartment());

        complaintRepository.save(complaint);

        return new MessageResponse(complaint);
    }

    @CachePut(value = "messageDetailsStudent", key = "{#complaintId, #studentEmail}")
    public MessageResponse openMessage(Long complaintId, String studentEmail) {
        Message complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new MessageNotFoundException("Complaint not found"));

        // Mark seen by student
        complaint.setSeenByStudent(true);
        complaintRepository.save(complaint);

        return new MessageResponse(complaint); // full view DTO
    }


    public Page<MessagePreviewDto> getMyComplaints(
            String email,
            String status,     // optional, ALL/PENDING/etc
            String sort,       // optional, NEWEST/OLDEST
            Pageable pageable) {

        Student student = studentRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(() -> new StudentNotFoundException("Student not found"));

        Page<Message> complaintsPage;

        // Determine sort direction
        Sort.Direction direction = "OLDEST".equalsIgnoreCase(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), direction, "sentAt");

        // Filter by status if specified
        if (status != null && !"ALL".equalsIgnoreCase(status)) {
            Message.Status enumStatus = Message.Status.valueOf(status.toUpperCase());
            complaintsPage = complaintRepository.findByStudentAndStatus(student, enumStatus, sortedPageable);
        } else {
            complaintsPage = complaintRepository.findByStudent(student, sortedPageable);
        }

        // Map to DTO
        return complaintsPage.map(MessagePreviewDto::new);
    }


    /*
       PROFILE
     */
    @Cacheable(value = "studentProfile", key = "#email")
    public StudentResponse getProfile(String email) {

        return studentRepository.findByEmailIgnoreCase(email)
                .map(StudentResponse::new)
                .orElseThrow(() -> new StudentNotFoundException("Student not found"));
    }

    @CacheEvict(value = "studentProfile", key = "#email")
    public StudentResponse updateProfile(String email, StudentUpdateDto dto, MultipartFile image, boolean removeProfile) {

        Student student = studentRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new StudentNotFoundException("Student not found"));

        student.setName(dto.getName());

        // Remove profile picture if requested
        if (removeProfile) {
            // Delete old profile picture file if exists
            if (student.getProfileImageUrl() != null) {
                fileStorageService.deleteFile(student.getProfileImageUrl());
            }
            student.setProfileImageUrl(null);
        }
        // Otherwise, update image if provided
        else if (image != null && !image.isEmpty()) {
            // Delete old profile picture file if exists
            if (student.getProfileImageUrl() != null) {
                fileStorageService.deleteFile(student.getProfileImageUrl());
            }
            String imageUrl = fileStorageService.storeProfileImage(image);
            student.setProfileImageUrl(imageUrl);
        }

        return new StudentResponse(studentRepository.save(student));
    }

}