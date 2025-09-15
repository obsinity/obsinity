package com.obsinity.service.core.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public static BeanPostProcessor objectMapperNullOmittingCustomizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ObjectMapper om) {
                    om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
                    om.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
                }
                return bean;
            }
        };
    }
}
