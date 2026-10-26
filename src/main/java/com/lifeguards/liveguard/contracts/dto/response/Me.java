package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Role;
import com.lifeguards.liveguard.contracts.enums.Capability;

public record Me(UUID userId, String nickname, Role role, List<UUID> channelIds, List<Capability> capabilities) {
	public Me(UUID userId, String nickname, Role role, List<UUID> channelIds) {
		this(userId, nickname, role, channelIds,
				role == Role.PLATFORM_ADMIN ? List.of(Capability.MONITOR, Capability.REVOKE) : List.of());
	}

	public Me {
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(nickname, "nickname");
		DtoValidation.requireNonNull(role, "role");
		channelIds = DtoValidation.immutableList(channelIds, "channelIds");
		capabilities = DtoValidation.immutableList(capabilities, "capabilities");
	}
}
