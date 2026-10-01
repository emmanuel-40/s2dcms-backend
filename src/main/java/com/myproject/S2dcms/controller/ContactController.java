package com.myproject.S2dcms.controller;

import com.myproject.S2dcms.Service.EmailProducerService;
import com.myproject.S2dcms.Service.UserActionService;
import com.myproject.S2dcms.dto.email.EmailMessage;
import com.myproject.S2dcms.dto.message.ContactRequest;
import com.myproject.S2dcms.model.ContactMessage;
import com.myproject.S2dcms.repository.ContactMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/contact")
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);

    private final ContactMessageRepository repository;
    private final EmailProducerService emailProducerService;
    private final UserActionService userActionService;

    @Value("${admin.email}")
    private String adminEmail;

    public ContactController(ContactMessageRepository repository, EmailProducerService emailProducerService, UserActionService userActionService) {
        this.repository = repository;
        this.emailProducerService = emailProducerService;
        this.userActionService = userActionService;
    }

    @PostMapping
    public ResponseEntity<String> sendMessage(@RequestBody ContactRequest request) {
        String email = request.getEmail();
        String actionType = "CONTACT_FORM";

        userActionService.checkRateLimit(email, actionType);

        ContactMessage msg = new ContactMessage();
        msg.setName(request.getName());
        msg.setEmail(request.getEmail());
        msg.setMessage(request.getMessage());

        repository.save(msg);

        EmailMessage emailMessage = new EmailMessage(
            adminEmail,
            "New Contact Form Submission from " + request.getName(),
            "CONTACT",
            null,
            request.getName(),
            request.getMessage(),
            request.getEmail()
        );

        try {
            emailProducerService.sendEmailMessage(emailMessage);
        } catch (Exception e) {
            // The message is already persisted above, so the submission itself succeeded. A broker
            // outage (or a missing SPRING_RABBITMQ_HOST, which silently falls back to localhost)
            // must not fail the request and tell the sender their message was lost when it was not.
            log.error("Contact message from {} was stored but the notification email could not be queued", request.getEmail(), e);
        }

        return ResponseEntity.ok("Message sent successfully");
    }
}
