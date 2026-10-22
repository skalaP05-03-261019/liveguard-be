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

/**
 * 채팅 SSE (/api/channels/{id}/events). 메시지 커밋 후 CHAT을 공개 범위에 맞춰 보내고, 방송 경로의 공개 범위 변경·복원
 * ({@link #integrated})과 강퇴(LIVE_ACCESS_BLOCKED → USER_KICKED, 대상 본인)를 0.6 형태로 전달한다.
 * 트랜잭션 안에서 부르면 커밋 후에 보내고 롤백되면 보내지 않는다.
 */
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

  @org.springframework.context.event.EventListener
  void reauthenticated(com.lifeguards.liveguard.common.security.SessionReauthenticatedEvent event) { registry.closeSession(event.previousSessionId()); }

  void integrated(UUID channelId,UUID author,boolean restricted,String event,java.util.Map<String,Object> data) {
    if(event.equals("LIVE_ACCESS_BLOCKED")) {
      registry.broadcast(channelId,s->s.userId().equals(author),"USER_KICKED",java.util.Collections.singletonMap("until",null),true);
    } else if(java.util.Set.of("CHAT","MESSAGE_VISIBILITY_CHANGED","MESSAGE_RESTORED","LIVE_ENDED").contains(event)) {
      String name=event.equals("MESSAGE_RESTORED")?"MESSAGE_VISIBILITY_CHANGED":event;
      registry.broadcast(channelId,s->!restricted||s.operator()||s.userId().equals(author),name,data,event.equals("LIVE_ENDED"));
    }
  }

	@PreDestroy
	void shutdown() {
		registry.shutdown();
	}

}
