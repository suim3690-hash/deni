package com.deni.backend.common;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfig implements WebMvcConfigurer {
	private final String[] allowedOrigins;

	public WebCorsConfig(@Value("${app.cors.allowed-origins}") String origins) {
		this.allowedOrigins = Arrays.stream(origins.split(","))
				.map(String::trim)
				.filter(origin -> !origin.isEmpty())
				.toArray(String[]::new);
		if (allowedOrigins.length == 0) throw new IllegalArgumentException("At least one CORS origin is required");
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(allowedOrigins)
				.allowedMethods("GET", "POST", "PATCH", "OPTIONS")
				.allowedHeaders("Content-Type", "Idempotency-Key", "Authorization", "X-Device-Id")
				.exposedHeaders("Location")
				.maxAge(3600);
	}
}
