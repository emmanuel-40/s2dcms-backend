package com.myproject.S2dcms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableCaching
@EnableScheduling
public class S2dcmsApplication {

	public static void main(String[] args) {
		SpringApplication.run(S2dcmsApplication.class, args);
	}

}
