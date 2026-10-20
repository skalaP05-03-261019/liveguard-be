package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;
import java.util.UUID;

public record ActionStatusQuery(UUID actionId) {
	public ActionStatusQuery {
		Objects.requireNonNull(actionId, "actionId");
	}
}
