package com.lifeguards.liveguard.contracts.enums;

/**
 * 검토의 결정 상태. 최종 결정이 저장될 때만 DECIDED이며 실행 상태(ActionStatus)와 분리한다.
 * INVALIDATED는 강퇴 검토만 쓴다(승인 뒤 재검증 실패, 방송 종료). 저장된 결정은 그대로 남는다.
 */
public enum ReviewStatus {
	PENDING, DECIDED, INVALIDATED
}
