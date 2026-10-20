package com.lifeguards.liveguard.platform;

import java.util.UUID;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE 구독자. operator는 그 채널의 담당 운영자(member_role=MODERATOR) 여부. */
record SseSubscriber(UUID userId, boolean operator, String sessionId, SseEmitter emitter) {
}
