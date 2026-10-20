package com.lifeguards.liveguard.auth;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.lifeguards.liveguard.contracts.enums.Role;

@Service
class JdbcUserDetailsService implements UserDetailsService {

	private final JdbcClient jdbc;

	JdbcUserDetailsService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public AuthUser loadUserByUsername(String username) {
		return jdbc.sql("SELECT id, username, password_hash, nickname, role FROM platform.users WHERE username = ?")
				.param(username)
				.query((rs, rowNum) -> new AuthUser(
						rs.getObject("id", UUID.class),
						rs.getString("username"),
						rs.getString("password_hash"),
						rs.getString("nickname"),
						Role.valueOf(rs.getString("role"))))
				.optional()
				.orElseThrow(() -> new UsernameNotFoundException("User not found"));
	}

}
