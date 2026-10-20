package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

public record ErrorResponse(ErrorBody error, String traceId) {
	public ErrorResponse {
		DtoValidation.requireNonNull(error, "error");
		DtoValidation.requireNonNull(traceId, "traceId");
	}

	public record ErrorBody(
			ErrorCode code,
			String message,
			boolean retryable,
			Instant until,
			Integer currentVersion) {
		public ErrorBody {
			DtoValidation.requireNonNull(code, "code");
			DtoValidation.requireNonNull(message, "message");
		}
	}
}
