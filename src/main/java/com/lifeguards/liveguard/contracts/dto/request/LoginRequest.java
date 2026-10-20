package com.lifeguards.liveguard.contracts.dto.request;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record LoginRequest(String username, String password) {
	public LoginRequest {
		DtoValidation.requireNonNull(username, "username");
		DtoValidation.requireNonNull(password, "password");
	}

	@Override
	public String toString() {
		return "LoginRequest[username=" + username + ", password=****]";
	}
}
