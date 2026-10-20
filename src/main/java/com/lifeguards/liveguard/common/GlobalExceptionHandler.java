package com.lifeguards.liveguard.common;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse;
import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse.ErrorBody;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 모든 오류를 openapi의 {@link ErrorResponse} 형식으로 바꾼다.
 * 오류 메시지와 로그에는 입력값(채팅 원문 포함)을 넣지 않고 필드명·제한값만 쓴다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final int MAX_FIELD_NAME_LENGTH = 64;

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex) {
		ErrorBody error = new ErrorBody(ex.code(), ex.getMessage(), ex.retryable(), ex.until(), ex.currentVersion());
		return respond(ex.status(), error);
	}

	@ExceptionHandler(InvalidContractException.class)
	ResponseEntity<Object> handleInvalidContract(InvalidContractException ex) {
		return respond(HttpStatus.BAD_REQUEST, error(ErrorCode.VALIDATION_FAILED, ex.getMessage()));
	}

	// Security 예외는 ExceptionTranslationFilter가 401/403으로 바꾸도록 그대로 던진다 (아래 catch-all이 500으로 삼키지 않게)
	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	void rethrowSecurityException(RuntimeException ex) {
		throw ex;
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex) {
		log.error("Unhandled exception: {}", describe(ex));
		return respond(HttpStatus.INTERNAL_SERVER_ERROR,
				error(ErrorCode.INTERNAL_ERROR, "Internal server error"));
	}

	/** 스프링 MVC가 던지는 표준 예외(본문 파싱, 타입 불일치, 경로 없음 등)를 모두 거친다. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
			return respond(HttpStatus.NOT_FOUND, error(ErrorCode.NOT_FOUND, "Resource not found"));
		}
		if (statusCode.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
			return respond(HttpStatus.SERVICE_UNAVAILABLE,
					error(ErrorCode.TEMPORARY_UNAVAILABLE, "Temporarily unavailable"));
		}
		if (statusCode.is5xxServerError()) {
			log.error("Unhandled framework exception: {}", describe(ex));
			return respond(HttpStatus.INTERNAL_SERVER_ERROR,
					error(ErrorCode.INTERNAL_ERROR, "Internal server error"));
		}
		// 405·406·415 등 나머지 4xx는 계약에 대응 코드가 없어 요청 오류로 묶는다
		return respond(HttpStatus.BAD_REQUEST, error(ErrorCode.VALIDATION_FAILED, validationMessage(ex)));
	}

	private static String validationMessage(Exception ex) {
		if (ex instanceof HttpMessageNotReadableException) {
			for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
				if (cause instanceof InvalidContractException) {
					return cause.getMessage();
				}
				if (cause instanceof UnrecognizedPropertyException unknown) {
					return "Unknown field: " + abbreviate(unknown.getPropertyName());
				}
			}
			return "Malformed request body";
		}
		if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
			return "Invalid value for '" + mismatch.getName() + "'";
		}
		if (ex instanceof MissingServletRequestParameterException missing) {
			return "Missing parameter: " + missing.getParameterName();
		}
		return "Invalid request";
	}

	private static ErrorBody error(ErrorCode code, String message) {
		return new ErrorBody(code, message, code == ErrorCode.TEMPORARY_UNAVAILABLE, null, null);
	}

	private static ResponseEntity<Object> respond(HttpStatus status, ErrorBody error) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_JSON)
				.body(ErrorResponses.of(error));
	}

	private static String abbreviate(String name) {
		if (name == null) {
			return "";
		}
		return name.length() <= MAX_FIELD_NAME_LENGTH ? name : name.substring(0, MAX_FIELD_NAME_LENGTH) + "...";
	}

	// 예외 메시지에는 DB 제약 위반 행(채팅 원문)이 들어올 수 있어 클래스와 발생 위치만 남긴다
	private static String describe(Throwable ex) {
		StringBuilder sb = new StringBuilder();
		Throwable current = ex;
		for (int depth = 0; current != null && depth < 5; depth++) {
			if (depth > 0) {
				sb.append(" <- ");
			}
			sb.append(current.getClass().getName());
			Arrays.stream(current.getStackTrace())
					.filter(frame -> frame.getClassName().startsWith("com.lifeguards"))
					.findFirst()
					.ifPresent(frame -> sb.append(" at ").append(frame));
			current = current.getCause();
		}
		return sb.toString();
	}

}
