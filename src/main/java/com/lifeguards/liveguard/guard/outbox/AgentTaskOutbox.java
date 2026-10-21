package com.lifeguards.liveguard.guard.outbox;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

@Service
public class AgentTaskOutbox {

	private final AgentOutboxRepository repository;
	private final Clock clock;

	public AgentTaskOutbox(AgentOutboxRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** The caller validates case readiness and persisted review/decision authority before enqueueing. */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID enqueue(AgentTaskEnvelope envelope) {
		DtoValidation.requireNonNull(envelope, "envelope");
		return repository.enqueue(envelope, clock.instant());
	}
}
