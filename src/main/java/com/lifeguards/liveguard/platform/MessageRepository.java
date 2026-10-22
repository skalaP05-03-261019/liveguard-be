package com.lifeguards.liveguard.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.ModerationReasonCode;
import com.lifeguards.liveguard.contracts.enums.Visibility;

@Repository
class MessageRepository {

	record HistoryRow(UUID id, UUID liveId, UUID authorId, String authorNickname, String text, Visibility visibility,
			int version, Instant sentAt, ModerationReasonCode moderationReasonCode) {
	}

	/** hide·restore가 바꾼 메시지. previous는 바뀌기 전 공개 범위. */
	record ChangedMessage(UUID id, UUID channelId, UUID authorId, Visibility previous, int version, String text,
			String authorNickname, Instant sentAt) {
	}

	record StoredMessage(UUID id, UUID liveId, String text, Visibility visibility, int version, Instant sentAt,
			ModerationReasonCode moderationReasonCode) {
	}

	private static final String COLUMNS = "id, live_id, text, visibility, version, sent_at, moderation_reason_code";

	private final JdbcClient jdbc;

	private final java.time.Clock clock;

	MessageRepository(JdbcClient jdbc, java.time.Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
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

	/** {@code since} 이후(초과) 같은 사용자·방송의 같은 정규화 문장 메시지 id (오래된 순). */
	List<UUID> sameTextMessageIds(UUID liveId, UUID authorId, String normalizedText, Instant since) {
		return jdbc.sql("SELECT id FROM platform.messages "
				+ "WHERE live_id = ? AND author_id = ? AND normalized_text = ? AND sent_at > ? "
				+ "ORDER BY sent_at, id")
				.params(liveId, authorId, normalizedText, Timestamp.from(since))
				.query(UUID.class)
				.list();
	}

	/**
	 * 사건 메시지 중 from 상태인 것을 target으로 바꾸고 version을 1 올린다. 행을 잠그고 바꾸므로 동시 호출은
	 * 순서대로 처리되며, 이미 target인 메시지는 대상이 아니다. excludePass이면 1차 필터를 통과한(PASS) 메시지도
	 * 대상에서 뺀다. 바뀐 메시지를 (sent_at, id) 순으로 돌려준다.
	 */
	List<ChangedMessage> changeCaseVisibility(UUID caseId, Visibility target, List<Visibility> from,
			boolean excludePass) {
		if(target==Visibility.PUBLIC) {
			jdbc.sql("UPDATE platform.message_hold_reasons SET released_at=:at WHERE case_id=:caseId AND reason<>'RULE_CONFIRMED' AND released_at IS NULL")
				.param("at",java.sql.Timestamp.from(clock.instant())).param("caseId",caseId).update();
		}
		return jdbc.sql("""
				WITH old AS (
					SELECT id, visibility FROM platform.messages
					WHERE (case_id = :caseId OR (:target = 'PUBLIC' AND EXISTS (
							SELECT 1 FROM platform.message_hold_reasons own_reason
							WHERE own_reason.message_id=platform.messages.id AND own_reason.case_id=:caseId)))
						AND visibility IN (:from)
						AND (:includePass OR filter_result <> 'PASS')
						AND (:target <> 'PUBLIC' OR (
							(COALESCE(moderation_reason_code,'') NOT IN ('PROFANITY_INCLUDED','LINK_NOT_ALLOWED')
								OR EXISTS (SELECT 1 FROM platform.message_hold_reasons released_rule
									WHERE released_rule.message_id=platform.messages.id
										AND released_rule.reason='RULE_CONFIRMED' AND released_rule.released_at IS NOT NULL))
							AND NOT EXISTS (SELECT 1 FROM platform.message_hold_reasons h
								WHERE h.message_id=platform.messages.id AND h.released_at IS NULL)))
					ORDER BY sent_at, id
					FOR UPDATE)
				UPDATE platform.messages m SET visibility = :target, version = m.version + 1
				FROM old WHERE m.id = old.id
				RETURNING m.id, m.channel_id, m.author_id, old.visibility AS previous, m.version, m.text, m.sent_at,
					(SELECT u.nickname FROM platform.users u WHERE u.id = m.author_id) AS nickname""")
				.param("caseId", caseId)
				.param("from", from.stream().map(Visibility::name).toList())
				.param("target", target.name())
				.param("includePass", !excludePass)
				.query((rs, rowNum) -> new ChangedMessage(
						rs.getObject("id", UUID.class),
						rs.getObject("channel_id", UUID.class),
						rs.getObject("author_id", UUID.class),
						Visibility.valueOf(rs.getString("previous")),
						rs.getInt("version"),
						rs.getString("text"),
						rs.getString("nickname"),
						rs.getObject("sent_at", OffsetDateTime.class).toInstant()))
				.list().stream()
				.sorted(Comparator.comparing(ChangedMessage::sentAt).thenComparing(ChangedMessage::id))
				.toList();
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

	/**
	 * 사건이 메시지를 가린 원인을 남긴다(E-2). 해제는 자기 사건의 원인만 풀 수 있다. 같은 (메시지, 사건)이 이미 있으면
	 * 건드리지 않는다. PASS는 가리지 않으므로 원인이 될 수 없다.
	 */
	void recordHoldReason(UUID messageId, UUID caseId, HandlingRoute route, Instant createdAt) {
		if (route == HandlingRoute.PASS) {
			throw new IllegalArgumentException("PASS cannot be a hold reason");
		}
		jdbc.sql("INSERT INTO platform.message_hold_reasons (message_id, case_id, reason, created_at) "
				+ "VALUES (?, ?, ?, ?) ON CONFLICT (message_id, case_id) DO NOTHING")
				.params(messageId, caseId, route.name(), Timestamp.from(createdAt))
				.update();
	}

	/** 같은 (채널, 작성자, clientMessageId)가 이미 있으면 저장하지 않고 empty를 돌려준다. */
	Optional<StoredMessage> insertIfAbsent(UUID channelId, UUID liveId, UUID authorId, UUID clientMessageId,
			String text, String normalizedText, FilterResult filterResult, Visibility visibility,
			ModerationReasonCode moderationReasonCode, Instant sentAt) {
		return jdbc.sql("INSERT INTO platform.messages (channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, moderation_reason_code, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
				+ "ON CONFLICT (channel_id, author_id, client_message_id) DO NOTHING RETURNING " + COLUMNS)
				.params(channelId, liveId, authorId, clientMessageId, text, normalizedText, filterResult.name(),
						visibility.name(), moderationReasonCode == null ? null : moderationReasonCode.name(),
						Timestamp.from(sentAt))
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
