package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.UUID;

/**
 * 채팅 SSE(/api/channels/{id}/events)로 B2가 보내는 이벤트 포트. platform이 정의한다.
 * (메시지 공개 범위 변경 이벤트는 방송 경로 {@link LivePlatformPort#visibility}가 platform 안에서 보내므로 여기에 없다.)
 */
public interface ChannelEventPublisher {

	/**
	 * 강퇴를 알리고 연결을 닫는다. 그 채널에서 그 사용자의 모든 채팅 SSE 연결에만 USER_KICKED(until)를 보낸 뒤
	 * 연결을 닫는다. 다른 사용자, 같은 사용자의 다른 채널 연결, 관제 SSE에는 영향이 없다.
	 *
	 * <p>
	 * 강퇴 트랜잭션 안에서 불러도 된다. <b>커밋 후에</b> 보내고 롤백되면 보내지 않는다. 재구독은
	 * {@link KickStatusQuery}가 막으므로(403 KICKED) 커밋 이후 그 구현에서 강퇴가 조회돼야 한다.
	 */
	void userKicked(UUID channelId, UUID userId, Instant until);

}
