package com.lifeguards.liveguard.contracts.dto.response;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ApiError(
		String code,
		String message,
		boolean retryable,
		String currentVersion,
		UUID actionId) {
	public ApiError {
		code = DtoValidation.requireText(code, "code");
		message = DtoValidation.requireText(message, "message");
	}
}
