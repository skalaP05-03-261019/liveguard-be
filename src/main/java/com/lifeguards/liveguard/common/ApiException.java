package com.lifeguards.liveguard.common;

import java.time.Instant;
import java.util.Objects;

import org.springframework.http.HttpStatus;

import com.lifeguards.liveguard.contracts.enums.ErrorCode;

/**
 * 계약의 {@link ErrorCode}로 응답해야 하는 업무 예외. 메시지에 채팅 원문 등 입력값을 넣지 않는다.
 */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode code;
	private final Instant until;
	private final Integer currentVersion;

	public ApiException(ErrorCode code, String message) {
		this(code, message, null, null);
	}

	/** @param until KICKED의 강퇴 만료 시각, @param currentVersion STALE_VERSION의 현재 버전 */
	public ApiException(ErrorCode code, String message, Instant until, Integer currentVersion) {
		super(Objects.requireNonNull(message, "message"));
		this.code = Objects.requireNonNull(code, "code");
		this.until = until;
		this.currentVersion = currentVersion;
	}

	public ErrorCode code() {
		return code;
	}

	public boolean retryable() {
		return code == ErrorCode.TEMPORARY_UNAVAILABLE;
	}

	public Instant until() {
		return until;
	}

	public Integer currentVersion() {
		return currentVersion;
	}

	public HttpStatus status() {
		return switch (code) {
			case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
			case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
			case FORBIDDEN, KICKED -> HttpStatus.FORBIDDEN;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case STALE_VERSION, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
			case REVIEW_NOT_APPROVED -> HttpStatus.UNPROCESSABLE_CONTENT;
			case TEMPORARY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

}
