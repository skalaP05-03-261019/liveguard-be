package com.lifeguards.liveguard.contracts.dto.response;

import java.util.Objects;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ApiErrorResponse(ApiError error, String traceId) {
	public ApiErrorResponse {
		Objects.requireNonNull(error, "error");
		traceId = DtoValidation.requireText(traceId, "traceId");
	}
}
