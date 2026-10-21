package com.lifeguards.liveguard.guard.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("liveguard.agent.delivery")
public record AgentTransportProperties(String transport) {
	public AgentTransportProperties {
		transport = transport == null ? "db" : transport;
		if (!transport.equals("db") && !transport.equals("rabbit")) {
			throw new IllegalArgumentException("Agent delivery transport must be db or rabbit");
		}
	}
}
