package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;
import java.util.Objects;

public record UserKickedEvent(Instant until) {
	public UserKickedEvent {
		Objects.requireNonNull(until, "until");
	}
}
