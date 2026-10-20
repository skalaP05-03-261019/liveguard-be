package com.lifeguards.liveguard.internal;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse;
import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse.ErrorBody;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BusinessToolController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BusinessToolExceptionHandler {

	@ExceptionHandler(BusinessToolNotImplementedException.class)
	@ApiResponse(responseCode = "501", description = "Contract only; no business operation has been executed")
	public ResponseEntity<ErrorResponse> notImplemented(BusinessToolNotImplementedException exception) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
				.body(new ErrorResponse(
						new ErrorBody(ErrorCode.NOT_IMPLEMENTED, exception.getMessage(), false, null, null),
						exception.traceId().toString()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	@ApiResponse(responseCode = "400", description = "Invalid business tool request")
	public ResponseEntity<ErrorResponse> invalidRequest(RuntimeException exception, HttpServletRequest request) {
		String traceId = request.getHeader("X-Trace-Id");
		return ResponseEntity.badRequest().body(new ErrorResponse(
				new ErrorBody(ErrorCode.VALIDATION_FAILED, exception.getMessage(), false, null, null),
				traceId == null ? UUID.randomUUID().toString() : traceId));
	}
}
