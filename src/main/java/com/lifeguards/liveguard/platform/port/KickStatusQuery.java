package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 채널 강퇴 상태 조회 포트. platform이 정의하고 guard(B2)가 구현한다 (kicks 테이블은 B2 소유).
 * 있으면 해당 채널의 채팅 전송과 채팅 SSE 구독이 403 KICKED(until 포함)로 거절된다.
 * B2 구현이 없으면 {@link InMemoryKickStatus}가 쓰인다.
 */
public interface KickStatusQuery {

	/**
	 * @return 아직 유효한 강퇴(해제되지 않았고 만료 전)의 만료 시각. 없으면 empty. 현재 시각은 주입된
	 *         {@link java.time.Clock} 기준이어야 한다.
	 */
	Optional<Instant> activeKickUntil(UUID channelId, UUID userId);

}
