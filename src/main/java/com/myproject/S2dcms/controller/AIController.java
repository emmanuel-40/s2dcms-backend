package com.myproject.S2dcms.controller;

import com.myproject.S2dcms.Exception.AiProviderException;
import com.myproject.S2dcms.Service.AIComplaintService;
import com.myproject.S2dcms.dto.ai.SummarizeRequest;
import com.myproject.S2dcms.dto.ai.SuggestReplyRequest;
import com.myproject.S2dcms.dto.ai.WriteComplaintRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private static final Logger logger = LoggerFactory.getLogger(AIController.class);
    private final AIComplaintService aiComplaintService;

    public AIController(AIComplaintService aiComplaintService) {
        this.aiComplaintService = aiComplaintService;
    }

    /**
     * Summarize a complaint
     */
    @PostMapping("/summarize")
    public ResponseEntity<String> summarizeComplaint(@RequestBody SummarizeRequest request) {
        logger.info("Received summarize request with text length: {}", request.getText() != null ? request.getText().length() : 0);
        String summary = aiComplaintService.summarizeComplaint(request.getText());
        logger.info("Summarize request completed successfully");
        return ResponseEntity.ok(summary);
    }

    /**
     * Suggest a reply for department staff
     */
    @PostMapping("/suggest-reply")
    public ResponseEntity<String> suggestReply(@RequestBody SuggestReplyRequest request) {
        logger.info("Received suggest-reply request with text length: {}", request.getComplaintText() != null ? request.getComplaintText().length() : 0);
        String reply = aiComplaintService.suggestReply(request.getComplaintText());
        logger.info("Suggest-reply request completed successfully");
        return ResponseEntity.ok(reply);
    }

    /**
     * Help a student write a complaint
     */
    @PostMapping("/write-complaint")
    public ResponseEntity<String> writeComplaint(@RequestBody WriteComplaintRequest request) {
        logger.info("Received write-complaint request with situation: {}", request.getSituation());
        String complaint = aiComplaintService.writeComplaint(request.getSituation());
        logger.info("Write-complaint request completed successfully");
        return ResponseEntity.ok(complaint);
    }

    /**
     * Turns provider failures into the status the SPA can act on (429 with {@code Retry-After},
     * 502, 503) without exposing which provider we use or why it failed. Declared here rather than
     * in {@code GlobalExceptionHandler} so the AI endpoints keep their own contract, and so these
     * never fall into that handler's catch-all {@code RuntimeException} to 400 mapping.
     */
    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<String> handleAiProviderFailure(AiProviderException ex) {
        return ex.toResponseEntity();
    }
}
