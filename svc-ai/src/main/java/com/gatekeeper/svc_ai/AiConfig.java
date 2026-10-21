package com.gatekeeper.svc_ai;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Providers are fakes so tests and demos cost nothing; swap in a real client implementing LlmProvider (key from an env var). */
@Configuration
public class AiConfig {

    @Bean @Order(1)
    LlmProvider primary(AiProps props) {
        return prompt -> {
            if (props.primaryFails()) throw new IllegalStateException("primary provider down");
            return "[primary] " + prompt;
        };
    }

    @Bean @Order(2)
    LlmProvider backup() {
        return prompt -> "[backup] " + prompt;
    }

    /** Producer owns the exchange; svc-notify owns its queue. */
    @Bean
    TopicExchange aiExchange() {
        return new TopicExchange(AiEvent.EXCHANGE);
    }

    @Bean
    MessageConverter jsonConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
