package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.lifeguards.liveguard.contracts.enums.AgentEventType;

public record AgentEventRequest(AgentEventType event, Map<String, Object> payload) {
	public AgentEventRequest {
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(payload, "payload");
		payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
