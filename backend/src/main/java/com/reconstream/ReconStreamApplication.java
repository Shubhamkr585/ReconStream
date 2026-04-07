package com.reconstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReconStreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconStreamApplication.class, args);
    }
}
