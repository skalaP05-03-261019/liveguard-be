package com.lifeguards.liveguard.internal;

import java.util.UUID;

final class BusinessToolNotImplementedException extends RuntimeException {
	private final UUID traceId;

	BusinessToolNotImplementedException(UUID traceId) {
		super("Business tool backend operation is not implemented");
		this.traceId = traceId == null ? UUID.randomUUID() : traceId;
	}

	UUID traceId() {
		return traceId;
	}
}
