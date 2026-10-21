package com.lifeguards.liveguard.guard.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rabbitmq.client.Channel;

public class AgentRabbitTransport implements ChannelAwareMessageListener {
	private static final Logger log = LoggerFactory.getLogger(AgentRabbitTransport.class);
	private final AgentOutboxRepository repository;
	private final AgentDeliveryWorker worker;
	private final RabbitTemplate template;
	private final AgentRabbitProperties rabbit;
	private final AgentDeliveryProperties delivery;
	private final Clock clock;

	public AgentRabbitTransport(AgentOutboxRepository repository, AgentDeliveryWorker worker, RabbitTemplate template,
			AgentRabbitProperties rabbit, AgentDeliveryProperties delivery, Clock clock) {
		this.repository = repository;
		this.worker = worker;
		this.template = template;
		this.rabbit = rabbit;
		this.delivery = delivery;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${liveguard.agent.delivery.poll-interval:1s}")
	public void poll() {
		for (int i = 0; i < delivery.batchSize(); i++) {
			try {
				if (!publishOne()) {
					return;
				}
			} catch (RuntimeException ex) {
				log.error("Agent notification publisher failed: type={}", ex.getClass().getName());
				return;
			}
		}
	}

	public boolean publishOne() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Rabbit publication must run outside a transaction");
		}
		var now = clock.instant();
		var claimed = repository.claimPublication(now, now.plus(rabbit.publishLease()));
		if (claimed.isEmpty()) {
			return false;
		}
		var publication = claimed.get();
		boolean confirmed = false;
		try {
			var properties = new MessageProperties();
			properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
			properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			var message = new Message(publication.id().toString().getBytes(StandardCharsets.US_ASCII), properties);
			var correlation = new CorrelationData(publication.token().toString());
			template.send(rabbit.exchange(), rabbit.routingKey(), message, correlation);
			var confirm = correlation.getFuture().get(rabbit.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
			// A confirm only acknowledges the broker, never the Agent Inbox. Returns precede confirms.
			confirmed = confirm.ack() && correlation.getReturned() == null;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (Exception ex) {
			// No broker reply, exception text, credentials or payload in application logs.
			log.warn("Agent notification unconfirmed: outboxId={} type={}",
					publication.id(), ex.getClass().getName());
		}
		if (!confirmed) {
			log.warn("Agent notification retry scheduled: outboxId={}", publication.id());
		}
		var finishedAt = clock.instant();
		repository.finishPublication(publication,
				finishedAt.plus(confirmed ? rabbit.recoveryInterval() : rabbit.retryInterval()), finishedAt);
		return true;
	}

	@Override
	public void onMessage(Message message, Channel channel) throws Exception {
		long tag = message.getMessageProperties().getDeliveryTag();
		UUID id;
		try {
			if (message.getBody().length != 36) {
				throw new IllegalArgumentException();
			}
			String body = new String(message.getBody(), StandardCharsets.US_ASCII);
			id = UUID.fromString(body);
			if (!id.toString().equals(body)) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException ex) {
			log.warn("Malformed Agent notification rejected");
			channel.basicReject(tag, false);
			return;
		}
		try {
			if (!worker.deliver(id)) {
				throw new IllegalStateException("Agent notification outcome was not persisted");
			}
		} catch (RuntimeException ex) {
			// Drop only the hint, never the DB work. The bounded DB publisher scan recovers it.
			log.error("Agent notification delivery failed: outboxId={} type={}", id, ex.getClass().getName());
			channel.basicReject(tag, false);
			return;
		}
		channel.basicAck(tag, false);
	}
}
