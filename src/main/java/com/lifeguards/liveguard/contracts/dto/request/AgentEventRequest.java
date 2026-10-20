package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.AgentEventType;

// openapi.yaml: AgentEventRequest는 additionalProperties를 막지 않는다 (나머지 요청 스키마는 false)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentEventRequest(AgentEventType event, Map<String, Object> payload) {
	public AgentEventRequest {
		DtoValidation.requireNonNull(event, "event");
		DtoValidation.requireNonNull(payload, "payload");
		payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
