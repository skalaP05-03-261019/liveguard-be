package com.lifeguards.liveguard.platform;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcChannelMembershipQuery implements ChannelMembershipQuery {

	private final JdbcClient jdbc;

	JdbcChannelMembershipQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<UUID> channelIdsOf(UUID userId) {
		return jdbc.sql("SELECT channel_id FROM platform.channel_members WHERE user_id = ? ORDER BY channel_id")
				.param(userId)
				.query(UUID.class)
				.list();
	}

}
