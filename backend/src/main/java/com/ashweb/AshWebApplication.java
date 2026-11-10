package com.ashweb;

import com.ashweb.config.AshWebProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AshWebProperties.class)
public class AshWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(AshWebApplication.class, args);
    }
}
