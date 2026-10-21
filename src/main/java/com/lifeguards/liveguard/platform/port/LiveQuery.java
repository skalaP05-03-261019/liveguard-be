package com.lifeguards.liveguard.platform.port;

import java.util.Optional;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.response.Live;

/**
 * 방송 조회 포트(E-1). platform이 정의하고 구현하며 guard(B2)가 쓴다. 진행 중이면 {@code endedAt() == null}이다.
 */
public interface LiveQuery {

	Optional<Live> find(UUID liveId);

	/** 그 채널의 진행 중 방송. 채널당 하나뿐이다. */
	Optional<Live> currentLive(UUID channelId);

}
