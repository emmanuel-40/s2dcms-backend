package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.*;
import com.myproject.S2dcms.dto.auth.ChangePasswordRequest;
import com.myproject.S2dcms.dto.department.DepartmentResponse;
import com.myproject.S2dcms.dto.message.MessagePreviewDto;
import com.myproject.S2dcms.dto.message.MessageResponse;
import com.myproject.S2dcms.dto.message.ReplyMessageRequest;
import com.myproject.S2dcms.dto.student.StudentResponse;
import com.myproject.S2dcms.dto.student.StudentUpdateDto;
import com.myproject.S2dcms.dto.verification.ForgotPasswordRequest;
import com.myproject.S2dcms.dto.verification.ResetPasswordRequest;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.Message;
import com.myproject.S2dcms.model.Message.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.myproject.S2dcms.model.RefreshToken;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.repository.DepartmentRepo;
import com.myproject.S2dcms.repository.MessageRepo;
import com.myproject.S2dcms.repository.RefreshTokenRepository;
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
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class DepartmentService {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentService.class);

    private final DepartmentRepo departmentRepository;

    private final MessageRepo complaintRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtUtil jwtUtil;

    private final UserActionService userActionService;

    private final RefreshTokenService tokenService;

    private final FileStorageService fileStorageService;

    private final MailService mailService;

    public DepartmentService(DepartmentRepo departmentRepository, MessageRepo complaintRepository, RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil, UserActionService userActionService, RefreshTokenService tokenService, FileStorageService fileStorageService, MailService mailService) {
        this.departmentRepository = departmentRepository;
        this.complaintRepository = complaintRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.userActionService = userActionService;
        this.tokenService = tokenService;
        this.fileStorageService = fileStorageService;
        this.mailService = mailService;
    }


    /* =========================
       PROFILE (READ ONLY)
       ========================= */

    @Cacheable(value = "departmentProfile", key = "#email")
    public DepartmentResponse getProfile(String email) {

        return departmentRepository.findByEmailIgnoreCase(email)
                .map(DepartmentResponse::new)
                .orElseThrow(() -> new DepartmentNotFoundException("Department not found"));
    }

    @CacheEvict(value = "departmentProfile", key = "#email")
    public DepartmentResponse updateProfile(String email, MultipartFile image, boolean removeProfile) {

        Department department = departmentRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new StudentNotFoundException("Department not found"));

        // Remove profile picture if requested
        if (removeProfile) {
            // Delete old profile picture file if exists
            if (department.getDepartmentProfile() != null) {
                fileStorageService.deleteFile(department.getDepartmentProfile());
            }
            department.setDepartmentProfile(null);
        }
        // Otherwise, update image if provided
        else if (image != null && !image.isEmpty()) {
            // Delete old profile picture file if exists
            if (department.getDepartmentProfile() != null) {
                fileStorageService.deleteFile(department.getDepartmentProfile());
            }
            String imageUrl = fileStorageService.storeProfileImage(image);
            department.setDepartmentProfile(imageUrl);
        }

        return new DepartmentResponse(departmentRepository.save(department));
    }



    public Page<MessagePreviewDto> getComplaints(
            String departmentEmail,
            String status,
            String sort,
            Pageable pageable) {

        Department department = departmentRepository
                .findByEmailIgnoreCase(departmentEmail)
                .orElseThrow(() -> new DepartmentNotFoundException("Department not found"));

        Page<Message> complaintsPage;

        Sort.Direction direction = "OLDEST".equalsIgnoreCase(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), direction, "sentAt");

        if (status != null && !"ALL".equalsIgnoreCase(status)) {
            Status enumStatus = Status.valueOf(status.toUpperCase());
            complaintsPage = complaintRepository.findByDepartmentAndStatus(department, enumStatus, sortedPageable);
        } else {
            complaintsPage = complaintRepository.findByDepartment(department, sortedPageable);
        }

        return complaintsPage.map(MessagePreviewDto::new);
    }


    @CachePut(value = "messageDetailsDept", key = "{#complaintId, #departmentEmail}")
    public MessageResponse openMessage(Long complaintId, String departmentEmail) {
        Message complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new MessageNotFoundException("Complaint not found"));

        // Mark seen by department
        complaint.setSeenByDepartment(true);
        if (complaint.getStatus()==Status.PENDING){
            complaint.setStatus(Status.IN_PROGRESS);
        }
        complaintRepository.save(complaint);

        return new MessageResponse(complaint);
    }

    /*
       REPLY TO COMPLAINT
      */

    @CacheEvict(value = {
            "messagesByStudent","messageDetailsStudent",
            "messageDetailsDept"
    }, allEntries = true)
    public MessageResponse replyToComplaint(
            ReplyMessageRequest request,
            MultipartFile attachment,
            String departmentEmail
    ) throws IOException {
        Department department = departmentRepository.findByEmailIgnoreCase(departmentEmail)
                .orElseThrow(() -> new DepartmentNotFoundException("Department not found"));

        Message complaint = complaintRepository.findById(request.getMessageId())
                .orElseThrow(() -> new ComplaintReplyException("Complaint not found"));

        if (!complaint.getDepartment().getId().equals(department.getId())){
            throw new RuntimeException("Unauthorized to reply to this complaint");
        }

        if (complaint.getStatus()==Status.PENDING){
            complaint.setStatus(Status.IN_PROGRESS);
        }

        complaint.setReply(request.getReply());

        String replyAttachmentPath = null;

        if (attachment != null && !attachment.isEmpty()) {
            // Delete old reply attachment if exists
            if (complaint.getReplyAttachmentPath() != null) {
                fileStorageService.deleteFile(complaint.getReplyAttachmentPath());
            }
            replyAttachmentPath = fileStorageService.storeAttachment(attachment);
        }

        complaint.setReplyAttachmentPath(replyAttachmentPath);
        complaint.setRepliedAt(LocalDateTime.now());
        complaint.setStatus(Status.REPLIED);
        complaint.setSeenByStudent(false); // Reset so student knows there's a new reply

        // Log field lengths for debugging
        logger.info("Saving complaint - title length: {}, content length: {}, attachmentPath length: {}, reply length: {}, replyAttachmentPath length: {}",
            complaint.getTitle() != null ? complaint.getTitle().length() : 0,
            complaint.getContent() != null ? complaint.getContent().length() : 0,
            complaint.getAttachmentPath() != null ? complaint.getAttachmentPath().length() : 0,
            complaint.getReply() != null ? complaint.getReply().length() : 0,
            complaint.getReplyAttachmentPath() != null ? complaint.getReplyAttachmentPath().length() : 0
        );

        complaintRepository.save(complaint);
        return new MessageResponse(complaint);
    }

    @CacheEvict(value = {
            "messagesByStudent","messageDetailsStudent",
            "messageDetailsDept"
    }, allEntries = true)
    public MessageResponse closeComplaint(Long messageId, String deptEmail) {

        Department dept = departmentRepository.findByEmailIgnoreCase(deptEmail)
                .orElseThrow(() -> new DepartmentNotFoundException("Department not found"));

        Message message = complaintRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found"));

        // Only the department that owns this complaint can close it
        if (!message.getDepartment().getId().equals(dept.getId())) {
            throw new MessageNotFoundException("Not your department message");
        }

        message.setStatus(Status.CLOSED);
        complaintRepository.save(message);

        return new MessageResponse(message);
    }

}
