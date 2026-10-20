package com.lifeguards.liveguard.platform.port;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** B2 구현 전 임시 구현: 강퇴 상태를 메모리에 둔다. 만료 판단은 주입된 Clock 기준이다. */
public class InMemoryKickStatus implements KickStatusQuery {

	private record Key(UUID channelId, UUID userId) {
	}

	private final Map<Key, Instant> kicks = new ConcurrentHashMap<>();
	private final Clock clock;

	public InMemoryKickStatus(Clock clock) {
		this.clock = clock;
	}

	@Override
	public Optional<Instant> activeKickUntil(UUID channelId, UUID userId) {
		return Optional.ofNullable(kicks.get(new Key(channelId, userId)))
				.filter(until -> until.isAfter(clock.instant()));
	}

	public void kick(UUID channelId, UUID userId, Instant until) {
		kicks.put(new Key(channelId, userId), until);
	}

	public void clear() {
		kicks.clear();
	}

}
