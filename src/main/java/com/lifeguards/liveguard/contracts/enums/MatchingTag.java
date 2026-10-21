package com.lifeguards.liveguard.contracts.enums;

/** 1차 필터가 걸린 조건. 계약에서는 문자열 목록(InternalCase.matchingTags)이며 값은 이 enum의 이름이다. */
public enum MatchingTag {
	ATTACK_TERM, CERTAIN_PROFANITY, OFFICIAL_CLAIM, CREDENTIAL_REQUEST, UNOFFICIAL_URL, REPEATED_TEXT
}
