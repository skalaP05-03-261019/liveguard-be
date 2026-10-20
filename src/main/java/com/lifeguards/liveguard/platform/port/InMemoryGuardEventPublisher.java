package com.lifeguards.liveguard.platform.port;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 11번 관제 SSE 전의 임시 구현: 최근 이벤트만 메모리에 보관한다. 로그에는 이벤트 이름과 채널만 남긴다. */
public class InMemoryGuardEventPublisher implements GuardEventPublisher {

	public record Published(UUID channelId, String event, Object data) {
	}

	private static final Logger log = LoggerFactory.getLogger(InMemoryGuardEventPublisher.class);
	private static final int MAX_RETAINED = 1000;

	private final Deque<Published> recent = new ArrayDeque<>();

	@Override
	public synchronized void publish(UUID channelId, String event, Object data) {
		recent.addLast(new Published(channelId, event, data));
		if (recent.size() > MAX_RETAINED) {
			recent.removeFirst();
		}
		log.debug("Guard event {} for channel {}", event, channelId);
	}

	public synchronized List<Published> recentEvents() {
		return List.copyOf(recent);
	}

	public synchronized void clear() {
		recent.clear();
	}

}
