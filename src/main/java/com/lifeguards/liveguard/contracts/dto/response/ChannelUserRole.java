package com.lifeguards.liveguard.contracts.dto.response;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;

public record ChannelUserRole(AuthorRole authorRole, boolean isOfficialOperator) {
	public ChannelUserRole {
		DtoValidation.requireNonNull(authorRole, "authorRole");
	}
}
