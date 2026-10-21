package com.lifeguards.liveguard.platform;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.lifeguards.liveguard.platform.port.GuardEventPublisher;

import jakarta.annotation.PreDestroy;

/**
 * 관제 SSE (/api/guard/events?channelId=). 구독자는 그 채널의 담당 운영자뿐이다.
 * 트랜잭션 안에서 publish하면 커밋 후에 보내고 롤백되면 보내지 않는다. 트랜잭션 밖이면 바로 보낸다.
 */
@Component
class GuardEventHub implements GuardEventPublisher {

	static final Set<String> EVENTS = Set.of("CASE_CREATED", "CASE_UPDATED", "TOOL_START", "TOOL_END",
			"KICK_REVIEW_REQUIRED", "KICK_APPLIED", "TASK_FAILED");

	private final SseRegistry registry;

	GuardEventHub(@Value("${liveguard.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
		this.registry = new SseRegistry("sse-guard", heartbeatInterval);
	}

	SseEmitter subscribe(UUID channelId, UUID moderatorId, String sessionId) {
		return registry.subscribe(channelId, moderatorId, true, sessionId);
	}

	int subscriberCount(UUID channelId) {
		return registry.subscriberCount(channelId);
	}

	@Override
	public void publish(UUID channelId, String event, Object data) {
		if (!EVENTS.contains(event)) {
			throw new IllegalArgumentException("Unknown guard event: " + event);
		}
		if (channelId == null || data == null) {
			throw new IllegalArgumentException("channelId and data are required");
		}
		AfterCommit.run(() -> registry.broadcast(channelId, subscriber -> true, event, data));
	}

	@EventListener
	void onSessionDestroyed(HttpSessionDestroyedEvent event) {
		registry.closeSession(event.getId());
	}

	@PreDestroy
	void shutdown() {
		registry.shutdown();
	}

}
