package com.odel.registration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(scanBasePackages = "com.odel", exclude = UserDetailsServiceAutoConfiguration.class)
public class RegistrationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegistrationServiceApplication.class, args);
    }
}
