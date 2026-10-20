package com.lifeguards.liveguard.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lifeguards.liveguard.contracts.enums.AuthorRole;

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

	Optional<AuthorRole> memberRole(UUID channelId, UUID userId) {
		return jdbc.sql("SELECT member_role FROM platform.channel_members WHERE channel_id = ? AND user_id = ?")
				.params(channelId, userId)
				.query((rs, rowNum) -> AuthorRole.valueOf(rs.getString("member_role")))
				.optional();
	}

}
