package com.obsinity.reference.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Reference service that wires controllers + storage + core. */
@SpringBootApplication(scanBasePackages = {"com.obsinity.controller", "com.obsinity.service"})
public class ObsinityApplication {
    public static void main(String[] args) {
        SpringApplication.run(ObsinityApplication.class, args);
    }
}
