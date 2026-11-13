package com.myproject.S2dcms.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // -------------------- Message Content --------------------
    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // -------------------- Student attachment --------------------
    @Column(columnDefinition = "TEXT")
    private String attachmentPath; // path to uploaded file/image by student

    // -------------------- Department reply --------------------
    @Column(columnDefinition = "TEXT")
    private String reply; // text reply from department
    @Column(columnDefinition = "TEXT")
    private String replyAttachmentPath; // optional file/image uploaded by department

    // -------------------- Status --------------------
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    // -------------------- Relations --------------------
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    // -------------------- Timestamps --------------------
    private LocalDateTime sentAt = LocalDateTime.now(); // when student sent
    private LocalDateTime repliedAt; // when department replied

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Column(nullable = false)
    private boolean seenByStudent = false;

    @Column(nullable = false)
    private boolean seenByDepartment = false;

    public enum Status{
        PENDING, IN_PROGRESS,REPLIED,CLOSED
    }

    // -------------------- Constructors --------------------
    public Message() {}

    public Message(String title, String content, Student student, Department department) {
        this.title = title;
        this.content = content;
        this.student = student;
        this.department = department;
    }

    // -------------------- Getters & Setters --------------------
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getAttachmentPath() { return attachmentPath; }
    public void setAttachmentPath(String attachmentPath) { this.attachmentPath = attachmentPath; }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public String getReplyAttachmentPath() { return replyAttachmentPath; }
    public void setReplyAttachmentPath(String replyAttachmentPath) { this.replyAttachmentPath = replyAttachmentPath; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }

    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }

    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public LocalDateTime getRepliedAt() { return repliedAt; }
    public void setRepliedAt(LocalDateTime repliedAt) { this.repliedAt = repliedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isSeenByStudent() {
        return seenByStudent;
    }

    public void setSeenByStudent(boolean seenByStudent) {
        this.seenByStudent = seenByStudent;
    }

    public boolean isSeenByDepartment() {
        return seenByDepartment;
    }

    public void setSeenByDepartment(boolean seenByDepartment) {
        this.seenByDepartment = seenByDepartment;
    }

}
