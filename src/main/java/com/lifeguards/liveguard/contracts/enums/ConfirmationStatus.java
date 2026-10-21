package com.lifeguards.liveguard.contracts.enums;

/** 사건 확정 상태(E-3). DEFER는 확정이 아니므로 PENDING을 유지한다. */
public enum ConfirmationStatus {
	PENDING, CONFIRMED_VIOLATION, CONFIRMED_NORMAL, CANCELLED
}
