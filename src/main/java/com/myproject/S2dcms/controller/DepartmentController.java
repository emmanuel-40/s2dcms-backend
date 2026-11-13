package com.myproject.S2dcms.controller;

import com.myproject.S2dcms.Service.DepartmentService;
import com.myproject.S2dcms.dto.admin.CreateDepartmentRequest;
import com.myproject.S2dcms.dto.admin.UpdateDepartmentPasswordRequest;
import com.myproject.S2dcms.dto.auth.ChangePasswordRequest;
import com.myproject.S2dcms.dto.department.DepartmentComplaintStatsResponse;
import com.myproject.S2dcms.dto.department.DepartmentResponse;
import com.myproject.S2dcms.dto.message.MessagePreviewDto;
import com.myproject.S2dcms.dto.message.MessageResponse;
import com.myproject.S2dcms.dto.message.ReplyMessageRequest;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.Role;
import com.myproject.S2dcms.repository.DepartmentRepo;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/department")
public class DepartmentController {

    private final DepartmentService departmentService;
    private final DepartmentRepo departmentRepo;
    private final PasswordEncoder passwordEncoder;

    public DepartmentController(DepartmentService departmentService, DepartmentRepo departmentRepo, PasswordEncoder passwordEncoder) {
        this.departmentService = departmentService;
        this.departmentRepo = departmentRepo;
        this.passwordEncoder = passwordEncoder;
    }

    /*
     * Public endpoint to get all departments (for registration dropdown)
     */
    @GetMapping("/all")
    public ResponseEntity<List<DepartmentResponse>> getAllDepartments() {
        try {
            List<Department> departments = departmentRepo.findAll();
            List<DepartmentResponse> response = departments.stream()
                    .filter(dept -> dept.getRole() != Role.ADMIN)
                    .map(dept -> new DepartmentResponse(
                            dept.getId(),
                            dept.getDepartmentName(),
                            dept.getEmail(),
                            dept.getDepartmentProfile()
                    ))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch departments. Please try again later.");
        }
    }

    /*
     * GET PROFILE
     */
    @GetMapping("/profile")
    public ResponseEntity<DepartmentResponse> getProfile(Principal principal) {
        return ResponseEntity.ok(departmentService.getProfile(principal.getName()));
    }

    @PutMapping("/profile")
    public ResponseEntity<DepartmentResponse> updateProfile(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "removeProfile", required = false) boolean removeProfile
    ) {

        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        return ResponseEntity.ok(departmentService.updateProfile(email, image, removeProfile));
    }

    /*
     * GET COMPLAINTS / MESSAGE HISTORY
     */
    @GetMapping("/complaints")
    public ResponseEntity<Page<MessagePreviewDto>> getComplaints(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "NEWEST") String sort,
            Pageable pageable,
            Principal principal) {

        Page<MessagePreviewDto> page = departmentService.getComplaints(
                principal.getName(),
                status,
                sort,
                pageable
        );

        return ResponseEntity.ok(page);
    }

    @GetMapping("/complaints/stats")
    public ResponseEntity<DepartmentComplaintStatsResponse> getComplaintStats(Principal principal) {
        return ResponseEntity.ok(departmentService.getComplaintStats(principal.getName()));
    }


    @GetMapping("/complaints/{id}")
    public ResponseEntity<MessageResponse> openMessage(@PathVariable Long id,Principal principal) {

        MessageResponse response = departmentService.openMessage(id, principal.getName());

        return ResponseEntity.ok(response);
    }

    /*
     * ==================== REPLY TO COMPLAINT ====================
     */
    @PostMapping("/reply")
    public ResponseEntity<MessageResponse> replyToComplaint(
            @ModelAttribute ReplyMessageRequest request,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment,
            Principal principal
    ) throws IOException {
        MessageResponse response = departmentService.replyToComplaint(
                request,attachment ,principal.getName());
        return ResponseEntity.ok(response);
    }


    /*
     * ==================== CLOSE COMPLAINT ====================
     */
    @PutMapping("/complaints/{complaintId}/close")
    public ResponseEntity<MessageResponse> closeComplaint(
            @PathVariable Long complaintId,
            Principal principal
    ) {
        // Calls service to set status = CLOSED
        return ResponseEntity.ok(departmentService.closeComplaint(complaintId, principal.getName()));
    }

    // ==================== ADMIN ENDPOINTS ====================
    
    // Paged departments for admin (excludes ADMIN), keeps /all unchanged for registration
    @GetMapping("/admin/page")
    public ResponseEntity<Page<DepartmentResponse>> getDepartmentsPage(Pageable pageable) {
        Page<Department> page = departmentRepo.findByRoleNot(Role.ADMIN, pageable);
        Page<DepartmentResponse> response = page.map(dept -> new DepartmentResponse(
                dept.getId(),
                dept.getDepartmentName(),
                dept.getEmail(),
                dept.getDepartmentProfile()
        ));
        return ResponseEntity.ok(response);
    }

    // Create a new department (Admin only)
    @PostMapping("/admin/create")
    public ResponseEntity<DepartmentResponse> createDepartment(@RequestBody CreateDepartmentRequest request) {
        Department department = new Department();
        department.setDepartmentName(request.getDepartmentName());
        department.setEmail(request.getEmail().toLowerCase());
        department.setPassword(passwordEncoder.encode(request.getPassword()));
        department.setDepartmentProfile(request.getDepartmentProfile());
        department.setRole(com.myproject.S2dcms.model.Role.DEPARTMENT);
        
        Department saved = departmentRepo.save(department);
        
        return ResponseEntity.ok(new DepartmentResponse(
                saved.getId(),
                saved.getDepartmentName(),
                saved.getEmail(),
                saved.getDepartmentProfile()
        ));
    }

    // Delete a department (Admin only)
    @DeleteMapping("/admin/{id}")
    public ResponseEntity<Void> deleteDepartment(@PathVariable Long id) {
        if (!departmentRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        departmentRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // Update department password (Admin only)
    @PutMapping("/admin/{id}/password")
    public ResponseEntity<Void> updateDepartmentPassword(
            @PathVariable Long id,
            @RequestBody UpdateDepartmentPasswordRequest request
    ) {
        Department department = departmentRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Department not found"));

        department.setPassword(passwordEncoder.encode(request.getNewPassword()));
        departmentRepo.save(department);

        return ResponseEntity.noContent().build();
    }

}
