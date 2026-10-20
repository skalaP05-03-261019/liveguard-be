package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.RoleLookup;

public record ChannelUserRoleResponse(RoleLookup role, boolean isOfficialOperator, String source,
		Instant verifiedAt) {
	public ChannelUserRoleResponse {
		Objects.requireNonNull(role, "role");
		source = DtoValidation.requireText(source, "source");
	}
}
