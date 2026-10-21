package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** 방송(E-1). endedAt이 null이면 방송 중이다. */
public record Live(UUID liveId, UUID channelId, Instant startedAt, Instant endedAt) {
	public Live {
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(startedAt, "startedAt");
		if (endedAt != null && endedAt.isBefore(startedAt)) {
			throw new InvalidContractException("endedAt must not be before startedAt");
		}
	}
}
