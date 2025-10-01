package com.obsinity.reference.client.spring;

import com.obsinity.client.core.ObsinityApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@ObsinityApplication
public class DemoApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(DemoApplication.class, args);
    }
}
