package com.lifeguards.liveguard.guard.outbox;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class AgentHttpGateway implements AutoCloseable {

	/**
	 * @param notSent 요청이 Agent에 확실히 닿지 않았다(연결 실패, 또는 연결이 끊긴 동안 보내지 않음). 이런 시도는 전달
	 *        예산을 쓰지 않는다
	 */
	public record Result(boolean accepted, boolean retryable, boolean uncertain, String code, boolean notSent,
			Instant retryAfter) {
		public Result(boolean accepted, boolean retryable, boolean uncertain, String code, boolean notSent) {
			this(accepted, retryable, uncertain, code, notSent, null);
		}
		public Result(boolean accepted, boolean retryable, boolean uncertain, String code) {
			this(accepted, retryable, uncertain, code, false);
		}

		static Result acceptedResult() {
			return new Result(true, false, false, null);
		}

		/** 이번 시도는 닿지 않았다. 이전 시도의 불확실 표시는 워커가 유지한다. */
		static Result unreachable() {
			return new Result(false, true, false, "AGENT_UNREACHABLE", true);
		}
	}

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentHttpGateway.class);

	private static final Set<String> AGENT_STATES = Set.of("QUEUED", "RUNNING", "WAITING_REVIEW",
			"SUCCEEDED", "NEEDS_REVIEW", "FAILED", "UNKNOWN");
	private static final int MAX_RESPONSE_BYTES = 64 * 1024;

	private final AgentDeliveryProperties properties;
	private final JsonMapper mapper;
	private final HttpClient client;
	private final Clock clock;
	// 연결 차단기: Agent에 연결하지 못하면 그 뒤 일정 시간은 네트워크를 쓰지 않고 바로 "닿지 않음"으로 답한다.
	// 연속으로 실패할수록 간격을 늘린다(최대 maxBackoff). 장애가 길어도 시도가 쌓여 새 작업을 밀어내지 않게 한다
	private final Object breaker = new Object();
	private int consecutiveUnreachable;
	private Instant reopenAt = Instant.MIN;
	// 응답을 받을 때마다 늘린다. 응답보다 먼저 시작한 연결 시도의 실패는 차단기를 다시 걸지 않는다
	private long responses;

	public AgentHttpGateway(AgentDeliveryProperties properties, JsonMapper mapper, Clock clock) {
		this.properties = properties;
		this.mapper = mapper;
		this.clock = clock;
		this.client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	public Result deliver(AgentTaskEnvelope envelope, boolean reconcileFirst, boolean allowPost) {
		if (breakerOpen()) {
			return Result.unreachable();
		}
		if (reconcileFirst) {
			Reply reply = exchange(envelope, "/agent/tasks/" + envelope.taskId(), false);
			if (reply.status() == 200 && matches(reply.body(), envelope)) {
				return Result.acceptedResult();
			}
			// GET returns the latest operation of a thread, possibly the run before this resume.
			if (reply.status() != 404 && !(reply.status() == 200 && previousOperation(reply.body(), envelope))) {
				return failure(reply, true);
			}
		}
		if (!allowPost) {
			return new Result(false, false, true, "DELIVERY_BUDGET_EXHAUSTED");
		}
		Reply reply = exchange(envelope, envelope.endpoint(), true);
		if ((reply.status() == 200 || reply.status() == 202) && matches(reply.body(), envelope)
				&& reply.body().path("duplicate").isBoolean()
				&& reply.body().path("duplicate").asBoolean() == (reply.status() == 200)) {
			return Result.acceptedResult();
		}
		return failure(reply, false);
	}

	private boolean matches(JsonNode body, AgentTaskEnvelope task) {
		return identityMatches(body, task) && task.operationId().equals(body.path("operationId").stringValue(""))
				&& AGENT_STATES.contains(body.path("status").stringValue(""));
	}

	private boolean identityMatches(JsonNode body, AgentTaskEnvelope task) {
		return body != null && task.taskId().toString().equals(body.path("taskId").stringValue(""))
				&& task.threadId().equals(body.path("threadId").stringValue(""));
	}

	private boolean previousOperation(JsonNode body, AgentTaskEnvelope task) {
		return identityMatches(body, task) && task.kind() == AgentTaskEnvelope.Kind.RESUME_REVIEW
				&& AGENT_STATES.contains(body.path("status").stringValue(""))
				&& !task.operationId().equals(body.path("operationId").stringValue(""))
				&& (body.path("operationId").stringValue("").equals("run:" + task.taskId())
						|| body.path("operationId").stringValue("").startsWith("resume:"));
	}

	private Result failure(Reply reply, boolean reconciliation) {
		if (reply.status() == 503 && reply.body() != null
				&& "INBOX_CAPACITY_EXCEEDED".equals(reply.body().path("detail").path("code").stringValue(""))) {
			return new Result(false, true, reconciliation, "INBOX_CAPACITY_EXCEEDED", false, reply.retryAfter());
		}
		// The human may decide after the review commits but before the Graph has
		// checkpointed its interrupt. This 409 proves the resume was not accepted.
		if (reply.status() == 409 && reply.body() != null
				&& "THREAD_NOT_WAITING".equals(reply.body().path("detail").path("code").stringValue(""))) {
			return new Result(false, true, false, "THREAD_NOT_WAITING");
		}
		if (reply.status() == 0 && "AGENT_UNREACHABLE".equals(reply.code())) {
			// 보내지 못했으므로 이번 시도로 불확실해지지 않는다(이전 시도가 불확실했으면 그 표시는 유지된다)
			return Result.unreachable();
		}
		if (reply.status() == 0 || Set.of(408, 429, 500, 502, 503, 504).contains(reply.status())) {
			return new Result(false, true, reconciliation || reply.status() != 429,
					reply.status() == 0 ? reply.code() : "AGENT_HTTP_" + reply.status(), false, reply.retryAfter());
		}
		if (reply.status() >= 200 && reply.status() < 300) {
			return new Result(false, true, true, "INVALID_AGENT_RECEIPT");
		}
		return new Result(false, false, reconciliation, "AGENT_HTTP_" + reply.status());
	}

	private record Reply(int status, JsonNode body, String code, Instant retryAfter) {
		Reply(int status, JsonNode body, String code) {
			this(status, body, code, null);
		}
	}

	private Reply exchange(AgentTaskEnvelope task, String path, boolean post) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(properties.baseUrl().resolve(path))
				.timeout(properties.requestTimeout())
				.header("Authorization", "Bearer " + properties.serviceToken())
				.header("X-Trace-Id", task.traceId().toString())
				.header("Accept", "application/json");
		if (post) {
			builder.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(task)));
		} else {
			builder.GET();
		}
		long seen = responsesSeen();
		Reply reply = send(builder);
		if ("AGENT_UNREACHABLE".equals(reply.code())) {
			breakerTrip(seen);
		} else if (reply.status() != 0) {
			// 응답을 받았을 때만 복구로 본다(중단·전송 오류·응답 시간 초과는 Agent 상태를 알려 주지 않는다)
			breakerReset();
		}
		return reply;
	}

	private Reply send(HttpRequest.Builder builder) {
		var future = client.sendAsync(builder.build(), info -> new LimitedBodySubscriber());
		try {
			HttpResponse<String> response = future.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
			Instant retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
			try {
				return new Reply(response.statusCode(), mapper.readTree(response.body()), null, retryAfter);
			} catch (JacksonException ex) {
				return new Reply(response.statusCode(), null, "INVALID_AGENT_RECEIPT", retryAfter);
			}
		} catch (ExecutionException ex) {
			// 연결 자체가 안 됐으면(거부·연결 시간 초과) 요청은 Agent에 닿지 않았다. 전달 여부가 확실하다
			for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
				if (cause instanceof java.net.ConnectException
						|| cause instanceof java.net.http.HttpConnectTimeoutException) {
					return new Reply(0, null, "AGENT_UNREACHABLE");
				}
			}
			return new Reply(0, null, "AGENT_TRANSPORT_ERROR");
		} catch (TimeoutException ex) {
			future.cancel(true);
			return new Reply(0, null, "AGENT_RESPONSE_TIMEOUT");
		} catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			return new Reply(0, null, "DELIVERY_INTERRUPTED");
		}
	}

	private Instant parseRetryAfter(String value) {
		if (value == null) {
			return null;
		}
		try {
			String text = value.strip();
			Instant result = text.matches("[0-9]+")
					? clock.instant().plusSeconds(Long.parseLong(text))
					: java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			// Keep persisted timestamps within the representable application/DB calendar range.
			if (result.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) {
				return null;
			}
			return result;
		} catch (java.time.DateTimeException | ArithmeticException | NumberFormatException ex) {
			log.warn("Invalid Agent Retry-After; using delivery backoff");
			return null;
		}
	}

	private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<String> {
		private final HttpResponse.BodySubscriber<String> delegate =
				HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
		private Flow.Subscription subscription;
		private long received;

		@Override
		public CompletionStage<String> getBody() {
			return delegate.getBody();
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			received += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
			if (received > MAX_RESPONSE_BYTES) {
				subscription.cancel();
				delegate.onError(new IOException("Agent response exceeds size limit"));
			} else {
				delegate.onNext(buffers);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			delegate.onComplete();
		}
	}

	/** 지금 연결 차단 중인지. */
	private boolean breakerOpen() {
		synchronized (breaker) {
			return consecutiveUnreachable > 0 && clock.instant().isBefore(reopenAt);
		}
	}

	/** 닿지 않은 작업을 다시 시도할 때까지 기다릴 시간. 차단이 풀리는 시각까지 남은 시간이며, 최소 initialBackoff다. */
	Duration unreachableBackoff() {
		synchronized (breaker) {
			Duration remaining = Duration.between(clock.instant(), reopenAt);
			return remaining.compareTo(properties.initialBackoff()) > 0 ? remaining : properties.initialBackoff();
		}
	}

	private long responsesSeen() {
		synchronized (breaker) {
			return responses;
		}
	}

	private void breakerTrip(long seenBefore) {
		synchronized (breaker) {
			if (responses != seenBefore) {
				return;
			}
			consecutiveUnreachable++;
			reopenAt = clock.instant().plus(properties.backoff(consecutiveUnreachable));
			if (consecutiveUnreachable == 1) {
				// 차단을 시작할 때 한 번만 남긴다(주소·포트 설정 오류도 여기서 드러난다)
				log.error("Agent unreachable; pausing delivery attempts: baseUrl={}", properties.baseUrl());
			}
		}
	}

	private void breakerReset() {
		synchronized (breaker) {
			responses++;
			if (consecutiveUnreachable > 0) {
				log.info("Agent reachable again after {} failed connection attempts", consecutiveUnreachable);
			}
			consecutiveUnreachable = 0;
		}
	}

	@Override
	public void close() {
		client.close();
	}
}
