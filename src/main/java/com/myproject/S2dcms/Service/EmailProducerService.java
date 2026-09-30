package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.securityConfig.RabbitMQConfig;
import com.myproject.S2dcms.dto.email.EmailMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EmailProducerService {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void sendEmailMessage(EmailMessage emailMessage) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE, emailMessage);
    }
}
