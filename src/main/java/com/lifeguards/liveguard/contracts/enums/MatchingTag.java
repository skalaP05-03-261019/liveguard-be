package com.lifeguards.liveguard.contracts.enums;

/**
 * 1차 필터가 걸린 조건. 계약에서는 문자열 목록(InternalCase.matchingTags)이며 값은 이 enum의 이름이다.
 * PII_PHONE·PII_ACCOUNT·PII_EMAIL은 개인정보 후보 형식(TBD-02 3순위)이다. 숫자 나열만으로는 붙지 않는다(AC-06).
 */
public enum MatchingTag {
	ATTACK_TERM, CERTAIN_PROFANITY, OFFICIAL_CLAIM, CREDENTIAL_REQUEST, LINK, REPEATED_TEXT,
	PII_PHONE, PII_ACCOUNT, PII_EMAIL
}
