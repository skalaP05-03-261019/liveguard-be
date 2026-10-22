package com.lifeguards.liveguard.guard.outbox;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

	public record Result(boolean accepted, boolean retryable, boolean uncertain, String code) {
		static Result acceptedResult() {
			return new Result(true, false, false, null);
		}
	}

	private static final Set<String> AGENT_STATES = Set.of("QUEUED", "RUNNING", "WAITING_REVIEW",
			"SUCCEEDED", "NEEDS_REVIEW", "FAILED", "UNKNOWN");
	private static final int MAX_RESPONSE_BYTES = 64 * 1024;

	private final AgentDeliveryProperties properties;
	private final JsonMapper mapper;
	private final HttpClient client;

	public AgentHttpGateway(AgentDeliveryProperties properties, JsonMapper mapper) {
		this.properties = properties;
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	public Result deliver(AgentTaskEnvelope envelope, boolean reconcileFirst, boolean allowPost) {
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
		// The human may decide after the review commits but before the Graph has
		// checkpointed its interrupt. This 409 proves the resume was not accepted.
		if (reply.status() == 409 && reply.body() != null
				&& "THREAD_NOT_WAITING".equals(reply.body().path("detail").path("code").stringValue(""))) {
			return new Result(false, true, false, "THREAD_NOT_WAITING");
		}
		if (reply.status() == 0 && "AGENT_UNREACHABLE".equals(reply.code())) {
			// 보내지 못했으므로 이번 시도로 불확실해지지 않는다(이전 시도가 불확실했으면 그 표시는 유지된다)
			return new Result(false, true, reconciliation, "AGENT_UNREACHABLE");
		}
		if (reply.status() == 0 || Set.of(408, 429, 500, 502, 503, 504).contains(reply.status())) {
			return new Result(false, true, reconciliation || reply.status() != 429,
					reply.status() == 0 ? reply.code() : "AGENT_HTTP_" + reply.status());
		}
		if (reply.status() >= 200 && reply.status() < 300) {
			return new Result(false, true, true, "INVALID_AGENT_RECEIPT");
		}
		return new Result(false, false, reconciliation, "AGENT_HTTP_" + reply.status());
	}

	private record Reply(int status, JsonNode body, String code) {
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
		var future = client.sendAsync(builder.build(), info -> new LimitedBodySubscriber());
		try {
			HttpResponse<String> response = future.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
			try {
				return new Reply(response.statusCode(), mapper.readTree(response.body()), null);
			} catch (JacksonException ex) {
				return new Reply(response.statusCode(), null, "INVALID_AGENT_RECEIPT");
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

	@Override
	public void close() {
		client.close();
	}
}
