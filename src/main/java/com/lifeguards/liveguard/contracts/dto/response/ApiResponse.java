package com.lifeguards.liveguard.contracts.dto.response;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ApiResponse<T>(T data, String traceId) {
	public ApiResponse {
		DtoValidation.requireNonNull(data, "data");
		traceId = DtoValidation.requireText(traceId, "traceId");
	}
}
