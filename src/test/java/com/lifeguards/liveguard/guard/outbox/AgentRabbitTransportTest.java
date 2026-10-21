package com.lifeguards.liveguard.guard.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.rabbitmq.client.Channel;

class AgentRabbitTransportTest {
	private final AgentOutboxRepository repository = mock(AgentOutboxRepository.class);
	private final AgentDeliveryWorker worker = mock(AgentDeliveryWorker.class);
	private final RabbitTemplate template = mock(RabbitTemplate.class);
	private final Instant now = Instant.parse("2026-10-21T00:00:00Z");
	private final AgentRabbitProperties properties = new AgentRabbitProperties(null, null, null,
			Duration.ofMillis(10), null, null, null, null);
	private final AgentDeliveryProperties delivery = new AgentDeliveryProperties(false, null, null,
			null, null, null, null, null, null, null, null, null);
	private final AgentRabbitTransport transport = new AgentRabbitTransport(repository, worker, template,
			properties, delivery, Clock.fixed(now, java.time.ZoneOffset.UTC));

	@Test
	void confirmsNacksTimeoutsAndReturnsOnlyControlNotificationRecovery() {
		for (String outcome : new String[] {"ack", "nack", "timeout", "return", "exception"}) {
			reset(repository, template);
			var publication = new AgentOutboxRepository.Publication(UUID.randomUUID(), UUID.randomUUID());
			when(repository.claimPublication(any(), any())).thenReturn(Optional.of(publication));
			doAnswer(invocation -> {
				Message message = invocation.getArgument(2);
				assertEquals(publication.id().toString(), new String(message.getBody(), StandardCharsets.US_ASCII));
				assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,
						message.getMessageProperties().getDeliveryMode());
				CorrelationData correlation = invocation.getArgument(3);
				assertEquals(publication.token().toString(), correlation.getId());
				if (outcome.equals("exception")) {
					throw new IllegalStateException("Do not log this");
				}
				if (outcome.equals("return")) {
					correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "exchange", "key"));
				}
				if (!outcome.equals("timeout")) {
					correlation.getFuture().complete(new CorrelationData.Confirm(!outcome.equals("nack"), null));
				}
				return null;
			}).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
			assertTrue(transport.publishOne());
			verify(repository).finishPublication(publication,
					now.plus(outcome.equals("ack") ? properties.recoveryInterval() : properties.retryInterval()), now);
			verifyNoInteractions(worker);
		}
	}

	@Test
	void ackOccursOnlyAfterWorkerReturnsAndFailureRejectsWithoutEndlessRequeue() throws Exception {
		UUID id = UUID.randomUUID();
		var channel = mock(Channel.class);
		var message = message(id.toString());
		when(worker.deliver(id)).thenReturn(true);
		transport.onMessage(message, channel);
		var order = inOrder(worker, channel);
		order.verify(worker).deliver(id);
		order.verify(channel).basicAck(7, false);
		reset(channel);
		when(worker.deliver(id)).thenThrow(new IllegalStateException("Persistence failed"));
		transport.onMessage(message, channel);
		verify(channel).basicReject(7, false);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
	}

	@Test
	void poisonBodiesAreRejectedWithoutCallingWorker() throws Exception {
		var channel = mock(Channel.class);
		for (String body : new String[] {"bad", "1-1-1-1-1", "x".repeat(36), UUID.randomUUID().toString().toUpperCase()}) {
			transport.onMessage(message(body), channel);
		}
		verify(channel, times(4)).basicReject(7, false);
		verifyNoInteractions(worker);
	}

	@Test
	void validatesTopologyDurationsAndTransport() {
		assertThrows(IllegalArgumentException.class, () -> new AgentTransportProperties("typo"));
		assertThrows(IllegalArgumentException.class, () -> new AgentRabbitProperties("", null, null,
				null, null, null, null, null));
		assertThrows(IllegalArgumentException.class, () -> new AgentRabbitProperties(null, null, null,
				Duration.ofSeconds(30), Duration.ofSeconds(30), null, null, null));
		assertThrows(IllegalArgumentException.class, () -> new AgentRabbitProperties(null, null, null,
				null, null, null, null, 0));
	}

	private Message message(String body) {
		var properties = new MessageProperties();
		properties.setDeliveryTag(7);
		return new Message(body.getBytes(StandardCharsets.US_ASCII), properties);
	}
}
