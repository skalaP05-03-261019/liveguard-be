package com.lifeguards.liveguard.internal;

import com.lifeguards.liveguard.common.ErrorResponses;
import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
				.body(ErrorResponses.of(ErrorCode.NOT_IMPLEMENTED, exception.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	@ApiResponse(responseCode = "400", description = "Invalid business tool request")
	public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(
				ErrorResponses.of(ErrorCode.VALIDATION_FAILED, exception.getMessage()));
	}
}
