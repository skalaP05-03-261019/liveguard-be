package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.UUID;

/**
 * 방송 종료 연동 이벤트(A-19). 방송 종료 트랜잭션 안에서 발행하며, 같은 liveId에 한 번만 나온다
 * (이미 끝난 방송의 종료 요청은 409 LIVE_ENDED). 소비자(B2)는 {@code @TransactionalEventListener}
 * (기본 AFTER_COMMIT)로 받는다. 롤백되면 전달되지 않는다.
 */
public record LiveEndedEvent(UUID liveId, UUID channelId, Instant endedAt) {
}
