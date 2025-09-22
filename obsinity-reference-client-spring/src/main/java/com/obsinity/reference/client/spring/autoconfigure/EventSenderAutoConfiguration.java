package com.obsinity.reference.client.spring.autoconfigure;

import com.obsinity.client.transport.EventSender;
import com.obsinity.client.transport.jdkhttp.JdkHttpEventSender;
import com.obsinity.client.transport.okhttp.OkHttpEventSender;
import com.obsinity.client.transport.webclient.WebClientEventSender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class EventSenderAutoConfiguration {

    @AutoConfiguration
    @ConditionalOnClass(WebClientEventSender.class)
    static class WebClientSenderConfig {
        @Bean
        @ConditionalOnMissingBean(EventSender.class)
        public EventSender webClientEventSender() {
            return new WebClientEventSender();
        }
    }

    @AutoConfiguration
    @ConditionalOnClass(OkHttpEventSender.class)
    static class OkHttpSenderConfig {
        @Bean
        @ConditionalOnMissingBean(EventSender.class)
        public EventSender okHttpEventSender() {
            return new OkHttpEventSender();
        }
    }

    @Bean
    @ConditionalOnMissingBean(EventSender.class)
    public EventSender jdkHttpEventSender() {
        return new JdkHttpEventSender();
    }
}
