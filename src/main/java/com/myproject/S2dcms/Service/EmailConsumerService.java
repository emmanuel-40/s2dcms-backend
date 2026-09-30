package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.dto.email.EmailMessage;
import com.myproject.S2dcms.securityConfig.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sendinblue.ApiException;

@Service
public class EmailConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(EmailConsumerService.class);

    @Autowired
    private MailService mailService;

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
    public void consumeEmailMessage(EmailMessage emailMessage) {
        try {
            logger.info("Processing email for: {} with type: {}", emailMessage.getTo(), emailMessage.getType());

            switch (emailMessage.getType()) {
                case "VERIFICATION":
                    mailService.sendVerificationEmail(emailMessage.getTo(), emailMessage.getToken());
                    logger.info("Verification email sent to: {}", emailMessage.getTo());
                    break;
                case "PASSWORD_RESET_STUDENT":
                    mailService.sendPasswordResetEmail(emailMessage.getTo(), emailMessage.getToken());
                    logger.info("Password reset email sent to student: {}", emailMessage.getTo());
                    break;
                case "PASSWORD_RESET_DEPARTMENT":
                    mailService.sendDepartmentPasswordResetEmail(emailMessage.getTo(), emailMessage.getToken());
                    logger.info("Password reset email sent to department: {}", emailMessage.getTo());
                    break;
                case "CONTACT":
                    mailService.sendContactEmail(emailMessage.getTo(), emailMessage.getName(), emailMessage.getSenderEmail(), emailMessage.getMessage());
                    logger.info("Contact email sent to: {}", emailMessage.getTo());
                    break;
                default:
                    logger.warn("Unknown email type: {}", emailMessage.getType());
            }
        } catch (ApiException e) {
            logger.error("Failed to send email to {}: {}", emailMessage.getTo(), e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing email message: {}", e.getMessage());
        }
    }
}
