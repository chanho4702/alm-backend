package com.platform.almbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlmBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlmBackendApplication.class, args);
    }
}
