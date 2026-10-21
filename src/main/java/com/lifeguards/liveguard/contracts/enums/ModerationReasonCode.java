package com.lifeguards.liveguard.contracts.enums;

/** 작성자 안내 사유(A-3). PASS는 null이다. 안내에 탐지 단어·개인정보 원문을 다시 넣지 않는다. */
public enum ModerationReasonCode {
	PROFANITY_INCLUDED, LINK_NOT_ALLOWED, FLOOD_REPEATED, CONTEXT_PENDING, PII_PENDING
}
