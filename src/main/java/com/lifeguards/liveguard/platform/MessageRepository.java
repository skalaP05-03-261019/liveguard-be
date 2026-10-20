package com.lifeguards.liveguard.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Visibility;

@Repository
class MessageRepository {

	record StoredMessage(UUID id, String text, Visibility visibility, int version, Instant sentAt) {
	}

	private static final String COLUMNS = "id, text, visibility, version, sent_at";

	private final JdbcClient jdbc;

	MessageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	boolean isMember(UUID channelId, UUID userId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform.channel_members WHERE channel_id = ? AND user_id = ?)")
				.params(channelId, userId)
				.query(Boolean.class)
				.single();
	}

	/** 같은 (채널, 작성자, clientMessageId)가 이미 있으면 저장하지 않고 empty를 돌려준다. */
	Optional<StoredMessage> insertIfAbsent(UUID channelId, UUID authorId, UUID clientMessageId, String text,
			String normalizedText, FilterResult filterResult, Visibility visibility, Instant sentAt) {
		return jdbc.sql("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
				+ "ON CONFLICT (channel_id, author_id, client_message_id) DO NOTHING RETURNING " + COLUMNS)
				.params(channelId, authorId, clientMessageId, text, normalizedText, filterResult.name(),
						visibility.name(), Timestamp.from(sentAt))
				.query((rs, rowNum) -> toStored(rs))
				.optional();
	}

	Optional<StoredMessage> find(UUID channelId, UUID authorId, UUID clientMessageId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM platform.messages "
				+ "WHERE channel_id = ? AND author_id = ? AND client_message_id = ?")
				.params(channelId, authorId, clientMessageId)
				.query((rs, rowNum) -> toStored(rs))
				.optional();
	}

	private static StoredMessage toStored(ResultSet rs) throws SQLException {
		return new StoredMessage(
				rs.getObject("id", UUID.class),
				rs.getString("text"),
				Visibility.valueOf(rs.getString("visibility")),
				rs.getInt("version"),
				rs.getObject("sent_at", OffsetDateTime.class).toInstant());
	}

}
