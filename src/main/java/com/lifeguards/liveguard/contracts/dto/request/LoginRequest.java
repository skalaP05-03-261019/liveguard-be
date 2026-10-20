package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;

public record LoginRequest(String username, String password) {
	public LoginRequest {
		Objects.requireNonNull(username, "username");
		Objects.requireNonNull(password, "password");
	}

	@Override
	public String toString() {
		return "LoginRequest[username=" + username + ", password=****]";
	}
}
