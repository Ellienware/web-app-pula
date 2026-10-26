package com.momocircle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MoMoCircleApplication {
    public static void main(String[] args) {
        SpringApplication.run(MoMoCircleApplication.class, args);
    }
}
