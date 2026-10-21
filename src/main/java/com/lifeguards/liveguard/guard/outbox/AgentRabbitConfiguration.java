package com.lifeguards.liveguard.guard.outbox;

import java.time.Clock;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(prefix = "liveguard.agent.delivery", name = "enabled")
@ConditionalOnProperty(prefix = "liveguard.agent.delivery", name = "enabled", havingValue = "true")
class AgentRabbitConfiguration {
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBooleanProperty(prefix = "liveguard.agent.delivery", name = "enabled")
	@ConditionalOnProperty(prefix = "liveguard.agent.delivery", name = "transport", havingValue = "rabbit")
	@EnableConfigurationProperties(AgentRabbitProperties.class)
	static class RabbitDelivery {
		@Bean
		Declarables agentOutboxTopology(AgentRabbitProperties properties) {
			var exchange = new DirectExchange(properties.exchange(), true, false);
			var queue = new Queue(properties.queue(), true, false, false);
			return new Declarables(exchange, queue,
					BindingBuilder.bind(queue).to(exchange).with(properties.routingKey()));
		}

		@Bean
		AgentRabbitTransport agentRabbitTransport(AgentOutboxRepository repository, AgentDeliveryWorker worker,
				ConnectionFactory connectionFactory, AgentRabbitProperties rabbit,
				AgentDeliveryProperties delivery, Clock clock) {
			if (!(connectionFactory instanceof CachingConnectionFactory caching)) {
				throw new IllegalArgumentException("Rabbit delivery requires a caching connection factory");
			}
			caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
			caching.setPublisherReturns(true);
			var template = new RabbitTemplate(connectionFactory);
			template.setUsePublisherConnection(true);
			template.setMandatory(true);
			template.setReturnsCallback(returned -> {
				// The correlated future handles returns; do not log broker message contents.
			});
			return new AgentRabbitTransport(repository, worker, template, rabbit, delivery, clock);
		}

		@Bean
		SimpleMessageListenerContainer agentOutboxListener(ConnectionFactory connectionFactory,
				AgentRabbitTransport transport, AgentRabbitProperties rabbit, AgentDeliveryProperties delivery) {
			var container = new SimpleMessageListenerContainer(connectionFactory);
			container.setQueueNames(rabbit.queue());
			container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
			container.setConcurrentConsumers(delivery.concurrency());
			container.setMaxConcurrentConsumers(delivery.concurrency());
			container.setPrefetchCount(rabbit.prefetch());
			container.setDefaultRequeueRejected(false);
			container.setMissingQueuesFatal(false);
			container.setShutdownTimeout(delivery.leaseDuration().toMillis());
			container.setMessageListener(transport);
			return container;
		}
	}
}
