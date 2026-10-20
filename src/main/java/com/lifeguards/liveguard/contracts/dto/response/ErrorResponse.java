package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;

import com.lifeguards.liveguard.contracts.enums.ErrorCode;

public record ErrorResponse(ErrorBody error, String traceId) {
	public ErrorResponse {
		Objects.requireNonNull(error, "error");
		Objects.requireNonNull(traceId, "traceId");
	}

	public record ErrorBody(
			ErrorCode code,
			String message,
			boolean retryable,
			Instant until,
			Integer currentVersion) {
		public ErrorBody {
			Objects.requireNonNull(code, "code");
			Objects.requireNonNull(message, "message");
		}
	}
}
