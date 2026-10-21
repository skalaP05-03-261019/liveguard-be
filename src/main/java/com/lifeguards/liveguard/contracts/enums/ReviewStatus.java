package com.lifeguards.liveguard.contracts.enums;

/** 검토의 결정 상태. 최종 결정이 저장될 때만 DECIDED이며 실행 상태(ActionStatus)와 분리한다. */
public enum ReviewStatus {
	PENDING, DECIDED
}
