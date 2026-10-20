package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Role;

public record Me(UUID userId, String nickname, Role role, List<UUID> channelIds) {
	public Me {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(nickname, "nickname");
		Objects.requireNonNull(role, "role");
		channelIds = DtoValidation.immutableList(channelIds, "channelIds");
	}
}
