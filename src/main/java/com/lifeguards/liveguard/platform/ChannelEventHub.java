package com.lifeguards.liveguard.platform;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;
import com.lifeguards.liveguard.contracts.enums.Visibility;

import jakarta.annotation.PreDestroy;

/** 채팅 SSE (/api/channels/{id}/events). 메시지 커밋 후 CHAT을 공개 범위에 맞춰 보낸다. */
@Component
class ChannelEventHub {

	private final SseRegistry registry;

	ChannelEventHub(@Value("${liveguard.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
		this.registry = new SseRegistry("sse-chat", heartbeatInterval);
	}

	SseEmitter subscribe(UUID channelId, UUID userId, boolean operator, String sessionId) {
		return registry.subscribe(channelId, userId, operator, sessionId);
	}

	void register(UUID channelId, SseSubscriber subscriber) {
		registry.register(channelId, subscriber);
	}

	void remove(UUID channelId, SseSubscriber subscriber) {
		registry.remove(channelId, subscriber);
	}

	int subscriberCount(UUID channelId) {
		return registry.subscriberCount(channelId);
	}

	@TransactionalEventListener
	void onMessageCreated(MessageCreatedEvent event) {
		deliverChat(event.channelId(), event.authorId(), event.chat());
	}

	@EventListener
	void onSessionDestroyed(HttpSessionDestroyedEvent event) {
		registry.closeSession(event.getId());
	}

	// HELD·HIDDEN 원문은 작성자와 그 채널의 담당 운영자에게만 보낸다
	void deliverChat(UUID channelId, UUID authorId, ChatEvent chat) {
		registry.broadcast(channelId,
				subscriber -> chat.visibility() == Visibility.PUBLIC || subscriber.operator()
						|| subscriber.userId().equals(authorId),
				"CHAT", chat);
	}

	@PreDestroy
	void shutdown() {
		registry.shutdown();
	}

}
