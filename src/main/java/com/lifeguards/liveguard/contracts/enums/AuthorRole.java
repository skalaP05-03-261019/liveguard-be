package com.lifeguards.liveguard.contracts.enums;

/**
 * 채널 안 역할. UNKNOWN은 DB(channel_members)에 없는 값이며, 사건 작성자의 현재 멤버십을 확인할 수 없을 때(탈퇴 등)
 * 응답에만 쓴다. UNKNOWN을 공식 운영자로도 일반 시청자(VIEWER)로도 추정하지 않는다.
 */
public enum AuthorRole {
	VIEWER, STREAMER, OFFICIAL_STAFF, MODERATOR, UNKNOWN
}
