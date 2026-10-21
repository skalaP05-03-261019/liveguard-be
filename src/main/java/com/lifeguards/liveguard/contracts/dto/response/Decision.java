package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;

/**
 * 서버에 저장된 운영자 결정. value는 kind=CONTEXT면 ContextDecision, KICK이면 KickDecision의 이름이다.
 * 재개는 이 decisionId로 업무 DB 저장본을 읽는다.
 */
public record Decision(
		UUID decisionId,
		UUID reviewId,
		ReviewKind kind,
		String value,
		UUID operatorId,
		Instant decidedAt) {
	public Decision {
		DtoValidation.requireNonNull(decisionId, "decisionId");
		DtoValidation.requireNonNull(reviewId, "reviewId");
		value = DtoValidation.requireDecisionValue(kind, value);
		DtoValidation.requireNonNull(operatorId, "operatorId");
		DtoValidation.requireNonNull(decidedAt, "decidedAt");
	}
}
