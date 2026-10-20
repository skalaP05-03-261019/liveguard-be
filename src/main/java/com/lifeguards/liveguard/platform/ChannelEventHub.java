package com.lifeguards.liveguard.platform;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;
import com.lifeguards.liveguard.contracts.enums.Visibility;

import jakarta.annotation.PreDestroy;

/**
 * 채널 SSE 구독자 레지스트리 (서버 한 대 기준, 메모리).
 * 모든 전송·하트비트·종료는 허브 전용 단일 스레드에서 처리한다. 커밋 후 리스너는 작업만 넘기고 바로
 * 돌아가므로 전송 요청의 응답이 SSE 전송을 기다리지 않고, 이벤트 순서는 유지된다.
 */
@Component
class ChannelEventHub {

	private static final Logger log = LoggerFactory.getLogger(ChannelEventHub.class);

	record Subscriber(UUID userId, boolean operator, String sessionId, SseEmitter emitter) {
	}

	private final Map<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
	private final AtomicLong eventIds = new AtomicLong();
	private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(daemon("sse-dispatch"));
	private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(daemon("sse-heartbeat"));

	ChannelEventHub(@Value("${liveguard.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
		long millis = heartbeatInterval.toMillis();
		heartbeat.scheduleAtFixedRate(() -> dispatcher.execute(this::sendHeartbeats), millis, millis,
				TimeUnit.MILLISECONDS);
	}

	SseEmitter subscribe(UUID channelId, UUID userId, boolean operator, String sessionId) {
		SseEmitter emitter = new SseEmitter(0L);
		try {
			// 프록시가 응답 헤더를 바로 내보내도록 첫 주석을 보낸다
			emitter.send(SseEmitter.event().comment("connected"));
		} catch (IOException ex) {
			emitter.completeWithError(ex);
			return emitter;
		}
		register(channelId, new Subscriber(userId, operator, sessionId, emitter));
		return emitter;
	}

	void register(UUID channelId, Subscriber subscriber) {
		subscribers.computeIfAbsent(channelId, id -> ConcurrentHashMap.newKeySet()).add(subscriber);
		Runnable remove = () -> remove(channelId, subscriber);
		subscriber.emitter().onCompletion(remove);
		subscriber.emitter().onTimeout(remove);
		subscriber.emitter().onError(error -> remove.run());
	}

	@TransactionalEventListener
	void onMessageCreated(MessageCreatedEvent event) {
		dispatcher.execute(() -> deliverChat(event.channelId(), event.authorId(), event.chat()));
	}

	@EventListener
	void onSessionDestroyed(HttpSessionDestroyedEvent event) {
		String sessionId = event.getId();
		dispatcher.execute(() -> subscribers.values().forEach(channel -> channel.stream()
				.filter(subscriber -> subscriber.sessionId().equals(sessionId))
				.forEach(subscriber -> subscriber.emitter().complete())));
	}

	int subscriberCount(UUID channelId) {
		return subscribers.getOrDefault(channelId, Set.of()).size();
	}

	// HELD·HIDDEN 원문은 작성자와 그 채널의 담당 운영자에게만 보낸다
	void deliverChat(UUID channelId, UUID authorId, ChatEvent chat) {
		String id = Long.toString(eventIds.incrementAndGet());
		for (Subscriber subscriber : subscribers.getOrDefault(channelId, Set.of())) {
			if (chat.visibility() == Visibility.PUBLIC || subscriber.operator()
					|| subscriber.userId().equals(authorId)) {
				send(channelId, subscriber,
						() -> SseEmitter.event().id(id).name("CHAT").data(chat, MediaType.APPLICATION_JSON));
			}
		}
	}

	private void sendHeartbeats() {
		subscribers.forEach((channelId, channel) -> channel
				.forEach(subscriber -> send(channelId, subscriber, () -> SseEmitter.event().comment(" ping"))));
	}

	private void send(UUID channelId, Subscriber subscriber, Supplier<SseEventBuilder> event) {
		try {
			subscriber.emitter().send(event.get());
		} catch (IOException | IllegalStateException ex) {
			// 끊긴 연결. 이벤트 내용(채팅 원문)은 로그에 남기지 않는다
			log.debug("Dropping SSE subscriber: {}", ex.getClass().getSimpleName());
			remove(channelId, subscriber);
		}
	}

	void remove(UUID channelId, Subscriber subscriber) {
		subscribers.computeIfPresent(channelId, (id, channel) -> {
			channel.remove(subscriber);
			return channel.isEmpty() ? null : channel;
		});
	}

	@PreDestroy
	void shutdown() {
		heartbeat.shutdownNow();
		dispatcher.shutdownNow();
	}

	private static ThreadFactory daemon(String name) {
		return runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};
	}

}
