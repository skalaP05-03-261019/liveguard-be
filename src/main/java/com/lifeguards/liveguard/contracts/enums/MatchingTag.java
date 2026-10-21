package com.lifeguards.liveguard.contracts.enums;

/**
 * 1차 필터가 걸린 조건. 계약에서는 문자열 목록(InternalCase.matchingTags)이며 값은 이 enum의 이름이다.
 * PII_PHONE·PII_ACCOUNT·PII_EMAIL은 PII 필터(TBD-02 3순위) 구현 전이라 아직 필터가 만들지 않는다.
 */
public enum MatchingTag {
	ATTACK_TERM, CERTAIN_PROFANITY, OFFICIAL_CLAIM, CREDENTIAL_REQUEST, LINK, REPEATED_TEXT,
	PII_PHONE, PII_ACCOUNT, PII_EMAIL
}
