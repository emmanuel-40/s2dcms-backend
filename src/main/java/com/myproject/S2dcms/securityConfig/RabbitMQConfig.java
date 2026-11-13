package com.myproject.S2dcms.securityConfig;

import org.springframework.amqp.core.*;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EMAIL_QUEUE = "email.queue";
    public static final String EMAIL_DLQ = "email.queue.dlq";
    public static final String EMAIL_DEAD_LETTER_EXCHANGE = "email.dead.letter";
    public static final String EMAIL_EXCHANGE = "email.exchange";
    public static final String EMAIL_ROUTING_KEY = "email.send";

    @Bean
    public DirectExchange emailExchange() {
        return new DirectExchange(EMAIL_EXCHANGE);
    }

    @Bean
    public DirectExchange emailDeadLetterExchange() {
        return new DirectExchange(EMAIL_DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue emailDlq() {
        return new Queue(EMAIL_DLQ, true);
    }

@Bean
    public Queue emailQueue() {
        // Plain durable queue — no DLX args. The local RabbitMQ already has an
        // email.queue declared without x-dead-letter-exchange, and redeclaring it
        // with that argument fails with PRECONDITION_FAILED. Retries are handled
        // in EmailConsumerService (bounded attempts + Redis dedup), so the DLQ is
        // not required for correctness.
        return new Queue(EMAIL_QUEUE, true);
    }

    @Bean
    public Binding emailBinding(Queue emailQueue, DirectExchange emailExchange) {
        return BindingBuilder.bind(emailQueue).to(emailExchange).with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public Binding emailDlqBinding(Queue emailDlq, DirectExchange emailDeadLetterExchange) {
        return BindingBuilder.bind(emailDlq).to(emailDeadLetterExchange).with(EMAIL_DLQ);
    }

    @Bean
    public MessageConverter converter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(converter());
        return rabbitTemplate;
    }
}
