package com.gatekeeper.svc_notify;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Consumer owns its queue and the dead-letter path for events it cannot process. */
@Configuration
public class RabbitConfig {
    static final String QUEUE = "orders.notify";
    static final String DLQ = "orders.notify.dlq";

    @Bean
    TopicExchange ordersExchange() {
        return new TopicExchange("orders");
    }

    @Bean
    Queue notifyQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange("") // default exchange routes straight to the DLQ by name
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding notifyBinding() {
        return BindingBuilder.bind(notifyQueue()).to(ordersExchange()).with("order.placed");
    }

    @Bean
    Queue aiQueue() {
        return QueueBuilder.durable("ai.notify").deadLetterExchange("").deadLetterRoutingKey(DLQ).build();
    }

    @Bean
    TopicExchange aiExchange() {
        return new TopicExchange("ai");
    }

    @Bean
    Binding aiBinding() {
        return BindingBuilder.bind(aiQueue()).to(aiExchange()).with("ai.event");
    }

    @Bean
    MessageConverter jsonConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
