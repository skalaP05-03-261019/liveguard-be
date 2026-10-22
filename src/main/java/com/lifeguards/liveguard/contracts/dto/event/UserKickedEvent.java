package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;

/**
 * 기존 채널 SSE(/api/channels/{id}/events)의 USER_KICKED payload(0.6 형태). v1 강퇴는 방송 종료까지라 만료 시각이 없으므로
 * {@code until}은 null이다(openapi: KICKED에는 until을 보내지 않는다).
 */
public record UserKickedEvent(Instant until) {
}
