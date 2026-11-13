package com.myproject.S2dcms.dto.email;

import java.util.UUID;

public class EmailMessage {
    private String to;
    private String subject;
    private String type; // VERIFICATION, PASSWORD_RESET, etc.
    private String token;
    private String name;
    private String message;
    private String senderEmail;
    private String eventId; // Stable UUID for deduplication - avoids token in Redis key

    public EmailMessage() {
        this.eventId = UUID.randomUUID().toString();
    }

    public EmailMessage(String to, String subject, String type, String token, String name) {
        this.eventId = UUID.randomUUID().toString();
        this.to = to;
        this.subject = subject;
        this.type = type;
        this.token = token;
        this.name = name;
    }

    public EmailMessage(String to, String subject, String type, String token, String name, String message) {
        this.eventId = UUID.randomUUID().toString();
        this.to = to;
        this.subject = subject;
        this.type = type;
        this.token = token;
        this.name = name;
        this.message = message;
    }

    public EmailMessage(String to, String subject, String type, String token, String name, String message, String senderEmail) {
        this.eventId = UUID.randomUUID().toString();
        this.to = to;
        this.subject = subject;
        this.type = type;
        this.token = token;
        this.name = name;
        this.message = message;
        this.senderEmail = senderEmail;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }
}
