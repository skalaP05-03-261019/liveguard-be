package com.lifeguards.liveguard.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Visibility;

@Repository
class MessageRepository {

	record HistoryRow(UUID id, UUID authorId, String authorNickname, String text, Visibility visibility, int version,
			Instant sentAt) {
	}

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

	/** 같은 사용자·채널의 전송을 트랜잭션 끝까지 직렬화한다 (도배 횟수를 정확히 세기 위해). */
	void lockAuthorInChannel(UUID channelId, UUID authorId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
				.param(channelId + ":" + authorId)
				.query((rs, rowNum) -> Boolean.TRUE)
				.single();
	}

	/** {@code since} 이후(초과) 같은 사용자·채널의 같은 정규화 문장 메시지 id (오래된 순). */
	List<UUID> sameTextMessageIds(UUID channelId, UUID authorId, String normalizedText, Instant since) {
		return jdbc.sql("SELECT id FROM platform.messages "
				+ "WHERE channel_id = ? AND author_id = ? AND normalized_text = ? AND sent_at > ? "
				+ "ORDER BY sent_at, id")
				.params(channelId, authorId, normalizedText, Timestamp.from(since))
				.query(UUID.class)
				.list();
	}

	/** case_id가 아직 비어 있는 메시지에만 사건을 연결한다 (이미 연결된 메시지는 바꾸지 않는다). */
	void linkCase(UUID channelId, UUID authorId, List<UUID> messageIds, UUID caseId) {
		if (messageIds.isEmpty()) {
			return;
		}
		jdbc.sql("UPDATE platform.messages SET case_id = :caseId "
				+ "WHERE id IN (:ids) AND channel_id = :channelId AND author_id = :authorId AND case_id IS NULL")
				.param("caseId", caseId)
				.param("ids", messageIds)
				.param("channelId", channelId)
				.param("authorId", authorId)
				.update();
	}

	Set<String> officialHosts(UUID channelId) {
		return new LinkedHashSet<>(jdbc.sql(
				"SELECT jsonb_array_elements_text(official_hosts) FROM platform.channels WHERE id = ?")
				.param(channelId)
				.query(String.class)
				.list());
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

	/**
	 * 최신순 이력. 담당 운영자가 아니면 PUBLIC과 본인 메시지만 SQL에서 거른다
	 * (타인의 HELD·HIDDEN 원문은 애플리케이션으로 읽어 오지도 않는다).
	 */
	List<HistoryRow> history(UUID channelId, UUID viewerId, boolean operator, MessageCursor after, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT m.id, m.author_id, u.nickname, m.text, m.visibility, m.version, m.sent_at
				FROM platform.messages m JOIN platform.users u ON u.id = m.author_id
				WHERE m.channel_id = :channelId""");
		if (!operator) {
			sql.append(" AND (m.visibility = 'PUBLIC' OR m.author_id = :viewerId)");
		}
		if (after != null) {
			sql.append(" AND (m.sent_at, m.id) < (:cursorAt, :cursorId)");
		}
		sql.append(" ORDER BY m.sent_at DESC, m.id DESC LIMIT :limit");

		var statement = jdbc.sql(sql.toString())
				.param("channelId", channelId)
				.param("limit", limit);
		if (!operator) {
			statement = statement.param("viewerId", viewerId);
		}
		if (after != null) {
			statement = statement.param("cursorAt", Timestamp.from(after.sentAt())).param("cursorId", after.id());
		}
		return statement.query((rs, rowNum) -> new HistoryRow(
				rs.getObject("id", UUID.class),
				rs.getObject("author_id", UUID.class),
				rs.getString("nickname"),
				rs.getString("text"),
				Visibility.valueOf(rs.getString("visibility")),
				rs.getInt("version"),
				rs.getObject("sent_at", OffsetDateTime.class).toInstant()))
				.list();
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
