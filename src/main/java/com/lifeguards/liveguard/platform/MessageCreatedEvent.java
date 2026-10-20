package com.lifeguards.liveguard.platform;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;

/**
 * 메시지가 새로 저장될 때 트랜잭션 안에서 발행한다. 6번(SSE)은 {@code @TransactionalEventListener}
 * (기본 AFTER_COMMIT)로 받아 수신자별로 전달한다. 재전송·롤백에서는 발행되지 않는다.
 * 원문 text가 들어 있으므로 로그에 남기지 않는다.
 */
public record MessageCreatedEvent(UUID channelId, UUID authorId, ChatEvent chat) {
}
