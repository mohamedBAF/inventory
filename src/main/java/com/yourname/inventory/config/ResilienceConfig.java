package com.yourname.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * LESSON 4 - switching on retries.
 *
 * Since Spring Framework 7 (Spring Boot 4) retry support is built into the framework:
 * {@code org.springframework.resilience.annotation.Retryable} and {@code @ConcurrencyLimit}.
 * You no longer add the {@code spring-retry} dependency, and you no longer write
 * {@code @EnableRetry} - this annotation replaces it.
 *
 * Differences vs. the old spring-retry library, worth knowing when you read older tutorials:
 *  - attribute is {@code maxRetries} (retries AFTER the first call), not {@code maxAttempts}
 *    (total calls). {@code maxRetries = 3} means up to 4 invocations.
 *  - backoff is configured inline ({@code delay}, {@code multiplier}, {@code jitter},
 *    {@code maxDelay}) instead of a nested {@code @Backoff}.
 *  - there is NO {@code @Recover} method. Fallback is written by hand - see
 *    {@code RestockService}, which catches the final exception and returns a degraded answer.
 */
@Configuration
@EnableResilientMethods
public class ResilienceConfig {
}
