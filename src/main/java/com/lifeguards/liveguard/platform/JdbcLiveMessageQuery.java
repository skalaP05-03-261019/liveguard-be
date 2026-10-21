package com.lifeguards.liveguard.platform;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.filter.PiiMasker;
import com.lifeguards.liveguard.platform.port.LiveMessageQuery;
import com.lifeguards.liveguard.platform.port.MessageView;

@Repository
class JdbcLiveMessageQuery implements LiveMessageQuery {

	private record Row(UUID liveId, MessageView view) {
	}

	private final JdbcClient jdbc;

	JdbcLiveMessageQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<MessageView> messages(UUID liveId, List<UUID> messageIds) {
		DtoValidation.requireNonNull(liveId, "liveId");
		List<UUID> ids = DtoValidation.immutableList(messageIds, "messageIds").stream().distinct().toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<UUID, Row> found = new LinkedHashMap<>();
		jdbc.sql("SELECT id, live_id, author_id, text, visibility, version, sent_at FROM platform.messages "
				+ "WHERE id IN (:ids)")
				.param("ids", ids)
				.query((rs, n) -> new Row(rs.getObject("live_id", UUID.class), new MessageView(
						rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
						PiiMasker.mask(rs.getString("text")), Visibility.valueOf(rs.getString("visibility")),
						rs.getInt("version"), rs.getObject("sent_at", OffsetDateTime.class).toInstant())))
				.list().forEach(row -> found.put(row.view().messageId(), row));

		// 예외 메시지에는 본문을 넣지 않는다
		if (found.values().stream().anyMatch(row -> !liveId.equals(row.liveId()))) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Message does not belong to the requested live");
		}
		if (found.size() != ids.size()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Message not found");
		}
		return ids.stream().map(id -> found.get(id).view()).toList();
	}

}
