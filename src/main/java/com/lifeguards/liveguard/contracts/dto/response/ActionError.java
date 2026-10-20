package com.lifeguards.liveguard.contracts.dto.response;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ActionError(String code, String message, boolean retryable) {
	public ActionError {
		code = DtoValidation.requireText(code, "code");
		message = DtoValidation.requireText(message, "message");
	}
}
