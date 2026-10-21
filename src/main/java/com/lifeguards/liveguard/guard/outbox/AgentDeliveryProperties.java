package com.lifeguards.liveguard.guard.outbox;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("liveguard.agent.delivery")
public record AgentDeliveryProperties(boolean enabled, URI baseUrl, String serviceToken,
		Duration connectTimeout, Duration requestTimeout, Duration leaseDuration,
		Duration pollInterval, Duration initialBackoff, Duration maxBackoff,
		Integer maxAttempts, Integer batchSize, Integer concurrency) {

	public AgentDeliveryProperties {
		connectTimeout = positive(connectTimeout, Duration.ofSeconds(3), "connectTimeout");
		requestTimeout = positive(requestTimeout, Duration.ofSeconds(10), "requestTimeout");
		leaseDuration = positive(leaseDuration, Duration.ofSeconds(60), "leaseDuration");
		pollInterval = positive(pollInterval, Duration.ofSeconds(1), "pollInterval");
		initialBackoff = positive(initialBackoff, Duration.ofSeconds(2), "initialBackoff");
		maxBackoff = positive(maxBackoff, Duration.ofMinutes(5), "maxBackoff");
		maxAttempts = maxAttempts == null ? 5 : maxAttempts;
		batchSize = batchSize == null ? 10 : batchSize;
		concurrency = concurrency == null ? 2 : concurrency;
		if (maxAttempts < 1 || batchSize < 1 || concurrency < 1 || maxBackoff.compareTo(initialBackoff) < 0
				|| leaseDuration.compareTo(requestTimeout.plus(connectTimeout).multipliedBy(2)) <= 0) {
			throw new IllegalArgumentException("Invalid Agent delivery limits or lease duration");
		}
		if (baseUrl != null && (!("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
				|| baseUrl.getHost() == null || baseUrl.getUserInfo() != null
				|| baseUrl.getPort() > 65535 || baseUrl.getPort() == 0
				|| baseUrl.getQuery() != null || baseUrl.getFragment() != null
				|| !(baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/")))) {
			throw new IllegalArgumentException("Agent base URL must be an HTTP origin without credentials");
		}
		if (serviceToken != null && serviceToken.chars().anyMatch(character -> character < 33 || character > 126)) {
			throw new IllegalArgumentException("Invalid Agent service token");
		}
		if (enabled && (baseUrl == null || serviceToken == null || serviceToken.isBlank())) {
			throw new IllegalArgumentException("Enabled Agent delivery requires base URL and service token");
		}
	}

	private static Duration positive(Duration value, Duration fallback, String name) {
		Duration resolved = value == null ? fallback : value;
		if (resolved.isNegative() || resolved.isZero() || resolved.toMillis() == 0) {
			throw new IllegalArgumentException("Agent delivery " + name + " must be positive");
		}
		return resolved;
	}

	public Duration backoff(int attempts) {
		Duration delay = initialBackoff;
		for (int i = 1; i < attempts && delay.compareTo(maxBackoff) < 0; i++) {
			delay = delay.compareTo(maxBackoff.dividedBy(2)) > 0 ? maxBackoff : delay.multipliedBy(2);
		}

		return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
	}

	@Override
	public String toString() {
		return "AgentDeliveryProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", serviceToken=REDACTED]";
	}
}
