package com.lifeguards.liveguard.common;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse;
import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse.ErrorBody;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

/** 현재 요청의 traceId를 붙인 {@link ErrorResponse}를 만든다. */
public final class ErrorResponses {

	private ErrorResponses() {
	}

	public static ErrorResponse of(ErrorCode code, String message) {
		return of(new ErrorBody(code, message, code == ErrorCode.TEMPORARY_UNAVAILABLE, null, null));
	}

	public static ErrorResponse of(ErrorBody error) {
		String traceId = TraceIdFilter.current();
		return new ErrorResponse(error, traceId != null ? traceId : UUID.randomUUID().toString());
	}

}
