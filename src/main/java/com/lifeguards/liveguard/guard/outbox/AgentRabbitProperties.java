package com.lifeguards.liveguard.guard.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("liveguard.agent.delivery.rabbit")
public record AgentRabbitProperties(String exchange, String queue, String routingKey, Duration confirmTimeout,
		Duration publishLease, Duration recoveryInterval, Duration retryInterval, Integer prefetch) {
	public AgentRabbitProperties {
		exchange = name(exchange, "liveguard.agent.outbox");
		queue = name(queue, "liveguard.agent.outbox");
		routingKey = name(routingKey, "outbox");
		confirmTimeout = duration(confirmTimeout, Duration.ofSeconds(5));
		publishLease = duration(publishLease, Duration.ofSeconds(30));
		recoveryInterval = duration(recoveryInterval, Duration.ofSeconds(60));
		retryInterval = duration(retryInterval, Duration.ofSeconds(5));
		prefetch = prefetch == null ? 1 : prefetch;
		if (prefetch < 1 || prefetch > 100 || publishLease.compareTo(confirmTimeout) <= 0) {
			throw new IllegalArgumentException("Invalid Rabbit prefetch or publish lease");
		}
	}

	private static String name(String value, String fallback) {
		String result = value == null ? fallback : value;
		if (!result.matches("[a-zA-Z0-9._-]{1,200}") || result.startsWith("amq.")) {
			throw new IllegalArgumentException("Invalid Rabbit topology name");
		}
		return result;
	}

	private static Duration duration(Duration value, Duration fallback) {
		Duration result = value == null ? fallback : value;
		if (result.isNegative() || result.isZero() || result.toMillis() == 0) {
			throw new IllegalArgumentException("Rabbit durations must be positive");
		}
		return result;
	}
}
