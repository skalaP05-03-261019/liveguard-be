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
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * 채널별 SSE 구독자 레지스트리 (서버 한 대 기준, 메모리). 채팅 SSE와 관제 SSE가 각자 하나씩 쓴다.
 * 모든 전송·하트비트·종료는 레지스트리 전용 단일 스레드에서 처리한다. 발행하는 쪽은 작업만 넘기고 바로
 * 돌아가므로 요청 응답이 SSE 전송을 기다리지 않고, 이벤트 순서는 유지된다.
 */
final class SseRegistry {

	private static final Logger log = LoggerFactory.getLogger(SseRegistry.class);

	private final Map<UUID, Set<SseSubscriber>> subscribers = new ConcurrentHashMap<>();
	private final AtomicLong eventIds = new AtomicLong();
	private final ExecutorService dispatcher;
	private final ScheduledExecutorService heartbeat;

	SseRegistry(String name, Duration heartbeatInterval) {
		this.dispatcher = Executors.newSingleThreadExecutor(daemon(name + "-dispatch"));
		this.heartbeat = Executors.newSingleThreadScheduledExecutor(daemon(name + "-heartbeat"));
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
		register(channelId, new SseSubscriber(userId, operator, sessionId, emitter));
		return emitter;
	}

	void register(UUID channelId, SseSubscriber subscriber) {
		subscribers.computeIfAbsent(channelId, id -> ConcurrentHashMap.newKeySet()).add(subscriber);
		Runnable remove = () -> remove(channelId, subscriber);
		subscriber.emitter().onCompletion(remove);
		subscriber.emitter().onTimeout(remove);
		subscriber.emitter().onError(error -> remove.run());
	}

	/** 레지스트리 스레드에서 채널 구독자 중 recipients에 해당하는 사람에게 보낸다. */
	void broadcast(UUID channelId, Predicate<SseSubscriber> recipients, String eventName, Object data) {
		dispatcher.execute(() -> {
			String id = Long.toString(eventIds.incrementAndGet());
			for (SseSubscriber subscriber : subscribers.getOrDefault(channelId, Set.of())) {
				if (recipients.test(subscriber)) {
					send(channelId, subscriber,
							() -> SseEmitter.event().id(id).name(eventName).data(data, MediaType.APPLICATION_JSON));
				}
			}
		});
	}

	void closeSession(String sessionId) {
		dispatcher.execute(() -> subscribers.values().forEach(channel -> channel.stream()
				.filter(subscriber -> subscriber.sessionId().equals(sessionId))
				.forEach(subscriber -> subscriber.emitter().complete())));
	}

	int subscriberCount(UUID channelId) {
		return subscribers.getOrDefault(channelId, Set.of()).size();
	}

	void remove(UUID channelId, SseSubscriber subscriber) {
		subscribers.computeIfPresent(channelId, (id, channel) -> {
			channel.remove(subscriber);
			return channel.isEmpty() ? null : channel;
		});
	}

	void shutdown() {
		heartbeat.shutdownNow();
		dispatcher.shutdownNow();
	}

	private void sendHeartbeats() {
		subscribers.forEach((channelId, channel) -> channel
				.forEach(subscriber -> send(channelId, subscriber, () -> SseEmitter.event().comment(" ping"))));
	}

	private void send(UUID channelId, SseSubscriber subscriber, Supplier<SseEventBuilder> event) {
		try {
			subscriber.emitter().send(event.get());
		} catch (IOException | IllegalStateException ex) {
			// 끊긴 연결. 이벤트 내용(채팅 원문 등)은 로그에 남기지 않는다
			log.debug("Dropping SSE subscriber: {}", ex.getClass().getSimpleName());
			remove(channelId, subscriber);
		}
	}

	private static ThreadFactory daemon(String name) {
		return runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};
	}

}
