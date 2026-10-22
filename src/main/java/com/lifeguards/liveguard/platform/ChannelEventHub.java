package com.lifeguards.liveguard.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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

import jakarta.annotation.PreDestroy;

/**
 * 채팅 SSE (/api/channels/{id}/events). 메시지 커밋 후 CHAT을 공개 범위에 맞춰 보내고, 방송 경로의 공개 범위 변경·복원
 * ({@link #integrated})과 강퇴(LIVE_ACCESS_BLOCKED → USER_KICKED, 대상 본인)를 0.6 형태로 전달한다.
 * CHAT은 커밋 후에 보낸다. {@link #integrated}는 받은 즉시 보내므로, 커밋 후 전송은 호출자({@code LiveService.emit})가 맡는다.
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
      registry.broadcast(channelId,s->s.userId().equals(author),"USER_KICKED",new UserKickedEvent(null),true);
    } else if(event.equals("MESSAGE_VISIBILITY_CHANGED")||event.equals("MESSAGE_RESTORED")) {
      // 0.6 형태는 둘 다 MESSAGE_VISIBILITY_CHANGED다. 복원이면 원문·작성자·보낸 시각을 함께 싣는다
      boolean restored=event.equals("MESSAGE_RESTORED");
      VisibilityChangedEvent payload=new VisibilityChangedEvent((UUID)data.get("messageId"),
          Visibility.valueOf(data.get("visibility").toString()),((Number)data.get("version")).intValue(),
          restored?(String)data.get("text"):null,restored?(String)data.get("authorAlias"):null,
          restored?(Instant)data.get("sentAt"):null);
      registry.broadcast(channelId,s->!restricted||s.operator()||s.userId().equals(author),"MESSAGE_VISIBILITY_CHANGED",payload);
    } else if(event.equals("CHAT")||event.equals("LIVE_ENDED")) {
      registry.broadcast(channelId,s->!restricted||s.operator()||s.userId().equals(author),event,data,event.equals("LIVE_ENDED"));
    }
  }

	@PreDestroy
	void shutdown() {
		registry.shutdown();
	}

}
