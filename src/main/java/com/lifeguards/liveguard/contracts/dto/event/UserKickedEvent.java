package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record UserKickedEvent(Instant until) {
	public UserKickedEvent {
		DtoValidation.requireNonNull(until, "until");
	}
}
