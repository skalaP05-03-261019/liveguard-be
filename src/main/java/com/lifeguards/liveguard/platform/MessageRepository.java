package com.lifeguards.liveguard.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lifeguards.liveguard.contracts.enums.ModerationReasonCode;
import com.lifeguards.liveguard.contracts.enums.Visibility;

@Repository
class MessageRepository {

	record HistoryRow(UUID id, UUID liveId, UUID authorId, String authorNickname, String text, Visibility visibility,
			int version, Instant sentAt, ModerationReasonCode moderationReasonCode) {
	}

	record StoredMessage(UUID id, UUID liveId, String text, Visibility visibility, int version, Instant sentAt,
			ModerationReasonCode moderationReasonCode) {
	}

	private static final String COLUMNS = "id, live_id, text, visibility, version, sent_at, moderation_reason_code";

	private final JdbcClient jdbc;


	MessageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 같은 사용자·채널의 전송을 트랜잭션 끝까지 직렬화한다 (도배 횟수를 정확히 세기 위해). */
	void lockAuthorInChannel(UUID channelId, UUID authorId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
				.param(channelId + ":" + authorId)
				.query((rs, rowNum) -> Boolean.TRUE)
				.single();
	}

	/** {@code since} 이후(초과) 같은 사용자·방송의 같은 정규화 문장 메시지 id (오래된 순). */
	List<UUID> sameTextMessageIds(UUID liveId, UUID authorId, String normalizedText, Instant since) {
		return jdbc.sql("SELECT id FROM platform.messages "
				+ "WHERE live_id = ? AND author_id = ? AND normalized_text = ? AND sent_at > ? "
				+ "ORDER BY sent_at, id")
				.params(liveId, authorId, normalizedText, Timestamp.from(since))
				.query(UUID.class)
				.list();
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
	 * (타인의 HELD·HIDDEN 원문은 애플리케이션으로 읽어 오지도 않는다). liveId가 null이면 채널 전체(기존 경로).
	 */
	List<HistoryRow> history(UUID channelId, UUID liveId, UUID viewerId, boolean operator, MessageCursor after,
			int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT m.id, m.live_id, m.author_id, u.nickname, m.text, m.visibility, m.version, m.sent_at,
					m.moderation_reason_code
				FROM platform.messages m JOIN platform.users u ON u.id = m.author_id
				WHERE m.channel_id = :channelId""");
		if (liveId != null) {
			sql.append(" AND m.live_id = :liveId");
		}
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
		if (liveId != null) {
			statement = statement.param("liveId", liveId);
		}
		if (!operator) {
			statement = statement.param("viewerId", viewerId);
		}
		if (after != null) {
			statement = statement.param("cursorAt", Timestamp.from(after.sentAt())).param("cursorId", after.id());
		}
		return statement.query((rs, rowNum) -> new HistoryRow(
				rs.getObject("id", UUID.class),
				rs.getObject("live_id", UUID.class),
				rs.getObject("author_id", UUID.class),
				rs.getString("nickname"),
				rs.getString("text"),
				Visibility.valueOf(rs.getString("visibility")),
				rs.getInt("version"),
				rs.getObject("sent_at", OffsetDateTime.class).toInstant(),
				reasonOf(rs.getString("moderation_reason_code"))))
				.list();
	}

	private static StoredMessage toStored(ResultSet rs) throws SQLException {
		return new StoredMessage(
				rs.getObject("id", UUID.class),
				rs.getObject("live_id", UUID.class),
				rs.getString("text"),
				Visibility.valueOf(rs.getString("visibility")),
				rs.getInt("version"),
				rs.getObject("sent_at", OffsetDateTime.class).toInstant(),
				reasonOf(rs.getString("moderation_reason_code")));
	}

	private static ModerationReasonCode reasonOf(String code) {
		return code == null ? null : ModerationReasonCode.valueOf(code);
	}

}
