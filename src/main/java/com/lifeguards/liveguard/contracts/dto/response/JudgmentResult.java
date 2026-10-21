package com.lifeguards.liveguard.contracts.dto.response;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ConfirmationStatus;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * 판정 제출 결과(A-9, A-16). 이 제출로 만들어진 검토가 있으면 reviewId·reviewKind를 함께 주고, 없으면 둘 다 null이다.
 * 값이 있으면 Agent는 그 reviewId로 interrupt한다.
 */
public record JudgmentResult(ConfirmationStatus confirmationStatus, UUID reviewId, ReviewKind reviewKind) {
	public JudgmentResult {
		DtoValidation.requireNonNull(confirmationStatus, "confirmationStatus");
		if ((reviewId == null) != (reviewKind == null)) {
			throw new InvalidContractException("reviewId and reviewKind must be both present or both null");
		}
	}
}
