package com.xyzensun.emailcopilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan("com.xyzensun.emailcopilot.infrastructure.persistence.mapper")
public class EmailCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmailCopilotApplication.class, args);
    }
}
