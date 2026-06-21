package com.allan.rate_limiter_lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.allan.rate_limiter_lab.config")
public class RateLimiterLabApplication {

	public static void main(String[] args) {
		SpringApplication.run(RateLimiterLabApplication.class, args);
	}

}
