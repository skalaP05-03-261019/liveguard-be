package com.lifeguards.liveguard.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.enums.Role;

/** 세션에 저장되는 로그인 사용자. 권한은 {@code ROLE_<Role>}. */
public class AuthUser extends User implements AuthenticatedUser {

	private static final long serialVersionUID = 1L;

	private final UUID userId;
	private final String nickname;
	private final Role role;

	AuthUser(UUID userId, String username, String passwordHash, String nickname, Role role) {
		super(username, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		this.userId = userId;
		this.nickname = nickname;
		this.role = role;
	}

	@Override
	public UUID userId() {
		return userId;
	}

	@Override
	public String nickname() {
		return nickname;
	}

	@Override
	public Role role() {
		return role;
	}

}
