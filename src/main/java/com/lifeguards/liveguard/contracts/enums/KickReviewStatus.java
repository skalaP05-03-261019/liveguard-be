package com.lifeguards.liveguard.contracts.enums;

/**
 * v0.5 강퇴 검토 상태. v1.0에서는 ReviewStatus(결정)와 ActionStatus(실행)로 대체됐고
 * 레거시 KickResult에서만 쓴다. 삭제 여부는 B2 확인 후(A-12).
 */
@Deprecated
public enum KickReviewStatus {
	PENDING, APPROVED, REJECTED, EXECUTED, FAILED
}
