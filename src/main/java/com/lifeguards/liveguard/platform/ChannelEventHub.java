package com.lifeguards.liveguard.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;
import com.lifeguards.liveguard.contracts.dto.event.UserKickedEvent;
import com.lifeguards.liveguard.contracts.dto.event.VisibilityChangedEvent;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.platform.VisibilityRouting.Route;
import com.lifeguards.liveguard.platform.port.ChannelEventPublisher;

import jakarta.annotation.PreDestroy;

/**
 * 채팅 SSE (/api/channels/{id}/events). 메시지 커밋 후 CHAT을 공개 범위에 맞춰 보내고, 공개 범위 변경
 * (MESSAGE_VISIBILITY_CHANGED, platform 내부용)과 강퇴(USER_KICKED, B2용 {@link ChannelEventPublisher})를 전달한다.
 * 트랜잭션 안에서 부르면 커밋 후에 보내고 롤백되면 보내지 않는다.
 */
@Component
class ChannelEventHub implements ChannelEventPublisher {

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

	/** 공개 범위 규칙({@link VisibilityRouting})대로 MESSAGE_VISIBILITY_CHANGED를 보낸다. 내용은 호출 시점에 만든다. */
	void visibilityChanged(VisibilityChange change) {
		Route route = VisibilityRouting.of(change.previous(), change.current());
		if (route.audience() == VisibilityRouting.Audience.NOBODY) {
			return;
		}
		VisibilityChangedEvent event = route.includeContent()
				? new VisibilityChangedEvent(change.messageId(), change.current(), change.version(), change.text(),
						change.authorAlias(), change.sentAt())
				: new VisibilityChangedEvent(change.messageId(), change.current(), change.version(), null, null, null);
		Predicate<SseSubscriber> recipients = route.audience() == VisibilityRouting.Audience.EVERYONE
				? subscriber -> true
				: subscriber -> subscriber.operator() || subscriber.userId().equals(change.authorId());
		AfterCommit.run(() -> registry.broadcast(change.channelId(), recipients, "MESSAGE_VISIBILITY_CHANGED", event));
	}

	@Override
	public void userKicked(UUID channelId, UUID userId, Instant until) {
		Objects.requireNonNull(channelId, "channelId");
		Objects.requireNonNull(userId, "userId");
		UserKickedEvent event = new UserKickedEvent(until);
		AfterCommit.run(() -> registry.broadcast(channelId, subscriber -> subscriber.userId().equals(userId),
				"USER_KICKED", event, true));
	}

	@PreDestroy
	void shutdown() {
		registry.shutdown();
	}

}
