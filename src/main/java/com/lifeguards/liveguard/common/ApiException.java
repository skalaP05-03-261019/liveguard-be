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

	/** @param until TIMED_OUT의 TIMEOUT 만료 시각(KICKED는 방송 종료까지라 없음), @param currentVersion STALE_VERSION의 현재 버전 */
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
			case FORBIDDEN, KICKED, TIMED_OUT -> HttpStatus.FORBIDDEN;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case STALE_VERSION, IDEMPOTENCY_CONFLICT, CASE_NOT_READY, LIVE_ENDED, LIVE_ALREADY_ACTIVE -> HttpStatus.CONFLICT;
			case REVIEW_NOT_APPROVED -> HttpStatus.UNPROCESSABLE_CONTENT;
			case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
			case TEMPORARY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

}
