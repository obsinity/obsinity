package com.obsinity.reference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@Slf4j
@SpringBootApplication(scanBasePackages = {"com.obsinity"})
public class ReferenceServiceApplication {

    public static void main(String[] args) {
        long maxMemory = Runtime.getRuntime().maxMemory();
        log.info("Max memory: {} ({} bytes)", formatBytes(maxMemory), maxMemory);
        SpringApplication.run(ReferenceServiceApplication.class, args);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return String.format("%.2f GB", bytes / 1_073_741_824.0);
        } else if (bytes >= 1_048_576L) {
            return String.format("%.2f MB", bytes / 1_048_576.0);
        } else {
            return String.format("%d bytes", bytes);
        }
    }
}
